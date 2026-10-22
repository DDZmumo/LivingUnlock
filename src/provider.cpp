#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <ws2bth.h>
#include "auth.h"
#include "vault.h"
#include "broker_protocol.h"
#include "canonical_transcript.h"
#include "pairing_crypto.h"
#include "phone_messages.h"
#include "phone_vault.h"
#include "device_info.h"
#include "rfcomm_server.h"
#include "connection_window.h"
#include "bluetooth_radio_watcher.h"
#include "ble_advertiser.h"
#include "session_store.h"
#include "time_handshake.h"
#include "application_time_cache.h"
#include <chrono>
#include <shlwapi.h>
#include <propkey.h>
#include <shlguid.h>
#include <array>
#include <vector>
#include <map>
#include <string>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <new>

namespace {
long moduleReferences = 0;

// ── Async logger ─────────────────────────────────────────────────────────────
// Each Log() call formats a string and enqueues it in O(1) without any disk I/O.
// A dedicated background thread drains the queue and writes to disk in batches,
// so the hot RFCOMM connection / credential path is never stalled by file I/O.
struct AsyncLogger {
    std::mutex mx;
    std::condition_variable cv;
    std::vector<std::string> queue;
    std::thread worker;
    bool stop = false;

    AsyncLogger() {
        worker = std::thread([this] {
            // Ensure the log directory exists (best-effort).
            CreateDirectoryW(L"C:\\ProgramData\\WindowsLockPin", nullptr);
            for (;;) {
                std::vector<std::string> batch;
                {
                    std::unique_lock<std::mutex> lk(mx);
                    cv.wait(lk, [this] { return stop || !queue.empty(); });
                    batch.swap(queue);
                    if (stop && batch.empty()) break;
                }
                FILE* fp = nullptr;
                if (_wfopen_s(&fp, L"C:\\ProgramData\\WindowsLockPin\\provider.log", L"a") == 0 && fp) {
                    for (const auto& line : batch) fputs(line.c_str(), fp);
                    fclose(fp);
                }
                // If stop was requested, flush remaining items but don't loop again.
                if (stop) {
                    std::lock_guard<std::mutex> lk(mx);
                    if (queue.empty()) break;
                }
            }
        });
    }

    ~AsyncLogger() {
        { std::lock_guard<std::mutex> lk(mx); stop = true; }
        cv.notify_all();
        if (worker.joinable()) worker.join();
    }

    void write(std::string line) {
        { std::lock_guard<std::mutex> lk(mx); queue.push_back(std::move(line)); }
        cv.notify_one();
    }
} g_log;

void Log(const char* format, ...) {
    char msgBuf[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(msgBuf, sizeof(msgBuf), format, args);
    va_end(args);

    auto now = std::chrono::system_clock::now();
    auto time = std::chrono::system_clock::to_time_t(now);
    tm tmLocal{};
    localtime_s(&tmLocal, &time);
    char tsBuf[32];
    strftime(tsBuf, sizeof(tsBuf), "[%Y-%m-%d %H:%M:%S] ", &tmLocal);

    std::string line;
    line.reserve(64 + strlen(msgBuf));
    line += tsBuf;
    line += msgBuf;
    line += '\n';
    g_log.write(std::move(line));
}

bool IsWinlogonInputDesktop(std::wstring* desktopName = nullptr) noexcept {
    if (desktopName) desktopName->clear();
    HDESK desktop = OpenInputDesktop(0, FALSE, DESKTOP_READOBJECTS);
    if (!desktop) return false;

    wchar_t name[64]{};
    DWORD required = 0;
    const bool ok = GetUserObjectInformationW(
        desktop, UOI_NAME, name, static_cast<DWORD>(sizeof(name)), &required) != FALSE;
    CloseDesktop(desktop);
    if (!ok) return false;
    if (desktopName) *desktopName = name;
    return _wcsicmp(name, L"Winlogon") == 0;
}

// Out-of-context WinEvents are delivered by the gate thread's message pump.
// Rechecking the desktop after dispatch is sufficient; no work runs in the hook.
void CALLBACK DesktopChanged(HWINEVENTHOOK, DWORD, HWND, LONG, LONG, DWORD, DWORD) {}

enum Field : DWORD {
    Icon,
    Title,
    Status,
    Retry,
    Code,
    Submit,
    Help,
    FieldCount
};
const CREDENTIAL_PROVIDER_FIELD_TYPE types[] = {
    CPFT_TILE_IMAGE,
    CPFT_LARGE_TEXT,
    CPFT_SMALL_TEXT,
    CPFT_COMMAND_LINK,
    CPFT_PASSWORD_TEXT,
    CPFT_SUBMIT_BUTTON,
    CPFT_SMALL_TEXT
};
const wchar_t* labels[] = {
    L"LivingUnlock",
    L"LivingUnlock",
    L"手机呢...让我找找",
    L"再次发送",
    L"6位动态码，给我看看",
    L"提交",
    L"按指纹还是输动态码？都行，你选。"
};

class Provider;

class Credential final : public ICredentialProviderCredential2 {
    long references_ = 1;
    Provider* provider_ = nullptr;
    std::wstring sid_;
    std::wstring username_;
    bool authenticatorEnabled_ = false;
    bool phoneEnabled_ = false;
    std::array<wchar_t, 7> code_{};
    ICredentialProviderCredentialEvents* events_ = nullptr;
    std::wstring currentStatus_ = L"手机呢...让我找找";
    std::mutex statusMutex_;

    void Clear() noexcept {
        SecureZeroMemory(code_.data(), sizeof(code_));
        std::lock_guard<std::mutex> lock(statusMutex_);
        if (events_) events_->SetFieldString(this, Code, L"");
    }

public:
    Credential(Provider* provider, std::wstring sid, std::wstring username,
        bool authenticatorEnabled, bool phoneEnabled)
        : provider_(provider), sid_(std::move(sid)), username_(std::move(username)),
          authenticatorEnabled_(authenticatorEnabled), phoneEnabled_(phoneEnabled) {
        InterlockedIncrement(&moduleReferences);
        Log("Credential constructed for SID %ls, user %ls", sid_.c_str(), username_.c_str());
    }

    ~Credential() {
        {
            std::lock_guard<std::mutex> lock(statusMutex_);
            if (events_) events_->Release();
            events_ = nullptr;
        }
        SecureZeroMemory(code_.data(), sizeof(code_));
        InterlockedDecrement(&moduleReferences);
    }

    const std::wstring& GetSid() const noexcept { return sid_; }
    std::string GetDisplayUsername() const { return lockpin::phone::InfoUtf8(username_); }
    bool HasPhone() const noexcept { return phoneEnabled_; }
    bool HasAuthenticator() const noexcept { return authenticatorEnabled_; }

    void SetStatusText(const std::wstring& text) {
        if (!phoneEnabled_) return;
        Log("Credential::SetStatusText for SID %ls: %ls", sid_.c_str(), text.c_str());
        std::lock_guard<std::mutex> lock(statusMutex_);
        currentStatus_ = text;
        if (events_ && phoneEnabled_) {
            events_->SetFieldString(this, Status, currentStatus_.c_str());
        }
    }

    IFACEMETHODIMP QueryInterface(REFIID iid, void** result) override {
        if (!result) return E_POINTER;
        *result = nullptr;
        if (iid == IID_IUnknown || iid == IID_ICredentialProviderCredential || iid == IID_ICredentialProviderCredential2)
            *result = static_cast<ICredentialProviderCredential2*>(this);
        if (!*result) return E_NOINTERFACE;
        AddRef(); return S_OK;
    }

    IFACEMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&references_); }
    IFACEMETHODIMP_(ULONG) Release() override {
        const auto n = InterlockedDecrement(&references_); if (!n) delete this; return n;
    }

    IFACEMETHODIMP Advise(ICredentialProviderCredentialEvents* events) override {
        std::lock_guard<std::mutex> lock(statusMutex_);
        if (events) events->AddRef();
        if (events_) events_->Release();
        events_ = events;
        if (events_) {
            events_->SetFieldString(this, Status, currentStatus_.c_str());
        }
        return S_OK;
    }

    IFACEMETHODIMP UnAdvise() override {
        std::lock_guard<std::mutex> lock(statusMutex_);
        if (events_) { events_->Release(); events_ = nullptr; }
        return S_OK;
    }

    // SetSelected is implemented out-of-line after Provider class
    IFACEMETHODIMP SetSelected(BOOL* automatic) override;
    IFACEMETHODIMP SetDeselected() override;

    IFACEMETHODIMP GetFieldState(DWORD field, CREDENTIAL_PROVIDER_FIELD_STATE* state,
        CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* interactive) override {
        if (field >= FieldCount) return E_INVALIDARG;
        const bool phoneField = field == Status || field == Retry;
        const bool authenticatorField = field == Code || field == Submit;
        if ((phoneField && !phoneEnabled_) || (authenticatorField && !authenticatorEnabled_))
            *state = CPFS_HIDDEN;
        else
            *state = field <= Title ? CPFS_DISPLAY_IN_BOTH : CPFS_DISPLAY_IN_SELECTED_TILE;
        *interactive = field == Code && authenticatorEnabled_ ? CPFIS_FOCUSED : CPFIS_NONE;
        return S_OK;
    }

    IFACEMETHODIMP GetStringValue(DWORD field, PWSTR* value) override {
        *value = nullptr;
        if (field >= FieldCount) return E_INVALIDARG;
        if (field == Code) return SHStrDupW(code_.data(), value);
        if (field == Status) {
            std::lock_guard<std::mutex> lock(statusMutex_);
            return SHStrDupW(phoneEnabled_ ? currentStatus_.c_str() : L"", value);
        }
        if (field == Help) {
            const wchar_t* help = phoneEnabled_ && authenticatorEnabled_
                ? L"按指纹还是输动态码？都行，你选。"
                : phoneEnabled_ ? L"手机上点“解锁”，按一下指纹，我就带你进桌面。"
                : L"把验证器里的6位动态码给我，我来核对。";
            const auto text = std::wstring(help) + L"\n想用PIN也可以，“登录选项”里就能切换，我不会拦着你。";
            return SHStrDupW(text.c_str(), value);
        }
        return SHStrDupW(labels[field], value);
    }

    IFACEMETHODIMP GetBitmapValue(DWORD field, HBITMAP* bitmap) override {
        *bitmap = nullptr;
        if (field != Icon) return E_INVALIDARG;
        HMODULE module = nullptr;
        if (GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                reinterpret_cast<LPCWSTR>(&Log), &module)) {
            *bitmap = LoadBitmapW(module, MAKEINTRESOURCEW(101));
            if (*bitmap) return S_OK;
        }
        // Synthetic provider tests have no compiled resource.
        BITMAPINFO info{};
        info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
        info.bmiHeader.biWidth = 64; info.bmiHeader.biHeight = -64;
        info.bmiHeader.biPlanes = 1; info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = BI_RGB;
        void* pixels = nullptr;
        *bitmap = CreateDIBSection(nullptr, &info, DIB_RGB_COLORS, &pixels, nullptr, 0);
        if (!*bitmap) return E_OUTOFMEMORY;
        auto data = static_cast<DWORD*>(pixels);
        for (int y = 0; y < 64; ++y) for (int x = 0; x < 64; ++x) {
            const bool dot = y >= 20 && y < 44 && x >= 13 && x < 51 &&
                ((x - 13) % 14 < 10) && ((y - 20) % 14 < 10);
            data[y * 64 + x] = dot ? 0xffffffff : 0xff2466bc;
        }
        return S_OK;
    }

    IFACEMETHODIMP GetSubmitButtonValue(DWORD field, DWORD* adjacent) override {
        if (field != Submit) return E_INVALIDARG;
        *adjacent = Code; return S_OK;
    }

    IFACEMETHODIMP SetStringValue(DWORD field, PCWSTR text) override {
        if (field != Code || !text) return E_INVALIDARG;
        SecureZeroMemory(code_.data(), sizeof(code_));
        const auto length = wcsnlen_s(text, 7);
        if (length > 6) return E_INVALIDARG;
        for (std::size_t i = 0; i < length; ++i) if (text[i] < L'0' || text[i] > L'9') return E_INVALIDARG;
        memcpy(code_.data(), text, length * sizeof(wchar_t));
        return S_OK;
    }

    IFACEMETHODIMP GetCheckboxValue(DWORD, BOOL* checked, PWSTR* label) override {
        *checked = FALSE; *label = nullptr; return E_NOTIMPL;
    }
    IFACEMETHODIMP SetCheckboxValue(DWORD, BOOL) override { return E_NOTIMPL; }
    IFACEMETHODIMP GetComboBoxValueCount(DWORD, DWORD* count, DWORD* selected) override {
        *count = 0; *selected = 0; return E_NOTIMPL;
    }
    IFACEMETHODIMP GetComboBoxValueAt(DWORD, DWORD, PWSTR* text) override { *text = nullptr; return E_NOTIMPL; }
    IFACEMETHODIMP SetComboBoxSelectedValue(DWORD, DWORD) override { return E_NOTIMPL; }
    IFACEMETHODIMP CommandLinkClicked(DWORD field) override;
    IFACEMETHODIMP GetUserSid(PWSTR* sid) override { return SHStrDupW(sid_.c_str(), sid); }

    IFACEMETHODIMP GetSerialization(CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* response,
        CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* packed, PWSTR* message,
        CREDENTIAL_PROVIDER_STATUS_ICON* icon) override;

    IFACEMETHODIMP ReportResult(NTSTATUS status, NTSTATUS substatus, PWSTR* message,
        CREDENTIAL_PROVIDER_STATUS_ICON* icon) override {
        Log("Credential::ReportResult: status = 0x%08X, substatus = 0x%08X", status, substatus);
        *message = nullptr; *icon = CPSI_NONE; Clear();
        if (status < 0) {
            *icon = CPSI_ERROR;
            Log("Windows rejected credentials! Status: 0x%08X", status);
            SetStatusText(L"密码没对上...先用PIN进桌面，把保存的密码更新一下");
            return SHStrDupW(L"密码没对上...先用PIN进桌面，把保存的密码更新一下", message);
        }
        Log("Logon SUCCEEDED! Workstation unlocked.");
        SetStatusText(L"已登录。");
        return S_OK;
    }
};

struct AutoUnlockState {
    std::mutex mutex;
    std::condition_variable cv;
    std::wstring targetSid;
    std::vector<BYTE> serializedData;
    CLSID providerClsid{};
    DWORD targetTileIndex = 0;
    bool ready = false;
};

class Provider final : public ICredentialProvider, public ICredentialProviderSetUserArray {
    lockpin::phone::ApplicationTimeEngine applicationTime_;
    long references_ = 1;
    ICredentialProviderUserArray* users_ = nullptr;
    std::vector<Credential*> credentials_;
    bool dirty_ = true;
    bool enabled_ = false;
    ICredentialProviderEvents* events_ = nullptr;
    UINT_PTR upAdviseContext_ = 0;

    AutoUnlockState autoUnlock_;
    std::atomic<bool> bgRunning_{false};
    std::thread bgThread_;
    std::mutex backgroundMutex_;
    HANDLE backgroundWake_ = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    lockpin::phone::RfcommServer bgServer_;
    lockpin::phone::BleAdvertiser bgBle_;   // BLE "locked" beacon → wakes Android instantly

    std::mutex pairedSidsMutex_;
    std::vector<std::wstring> pairedSids_;
    std::map<std::wstring, DWORD> sidTileIndices_;

    std::mutex credentialsMutex_;
    std::mutex activeSocketMutex_;
    SOCKET activeClientSocket_ = INVALID_SOCKET;
    std::atomic<std::uint64_t> activeRequestId_{0};
    std::atomic<std::uint64_t> retryGeneration_{0};

    std::mutex retryMutex_;
    std::condition_variable retryCv_;
    std::atomic<bool> waitingForRetry_{false};

    std::atomic<bool> interactionGateRunning_{false};
    std::thread interactionGateThread_;
    HANDLE interactionGateStop_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    std::mutex interactionGateMutex_;
    std::wstring interactionGateSid_;

    void StopInteractionGate() {
        interactionGateRunning_ = false;
        if (interactionGateStop_) SetEvent(interactionGateStop_);
        if (interactionGateThread_.joinable() &&
            interactionGateThread_.get_id() != std::this_thread::get_id()) {
            interactionGateThread_.join();
        }
        std::lock_guard<std::mutex> lock(interactionGateMutex_);
        interactionGateSid_.clear();
    }

    void StartInteractionGate(const std::wstring& sid) {
        {
            std::lock_guard<std::mutex> lock(interactionGateMutex_);
            if (interactionGateRunning_ && interactionGateSid_ == sid) return;
        }

        StopInteractionGate();
        {
            std::lock_guard<std::mutex> lock(interactionGateMutex_);
            interactionGateSid_ = sid;
        }
        interactionGateRunning_ = true;
        if (interactionGateStop_) ResetEvent(interactionGateStop_);
        interactionGateThread_ = std::thread([this, sid] {
            bool listenerStarted = false;
            std::wstring lastDesktop;
            const auto hook = SetWinEventHook(EVENT_SYSTEM_DESKTOPSWITCH, EVENT_SYSTEM_DESKTOPSWITCH,
                nullptr, DesktopChanged, 0, 0, WINEVENT_OUTOFCONTEXT);
            if (!hook) Log("Interaction gate: desktop notification unavailable; using one-second fallback");
            Log("Interaction gate started for SID %ls", sid.c_str());

            while (interactionGateRunning_) {
                std::wstring desktopName;
                const bool interactive = IsWinlogonInputDesktop(&desktopName);
                if (desktopName != lastDesktop) {
                    Log("Interaction gate input desktop: %ls",
                        desktopName.empty() ? L"(unavailable)" : desktopName.c_str());
                    lastDesktop = desktopName;
                }

                if (interactive && !listenerStarted) {
                    Log("Interaction gate opened: Winlogon is now the input desktop.");
                    SetStatusForSid(sid, L"手机呢...让我找找");
                    StartBackgroundListener();
                    listenerStarted = bgRunning_.load();
                } else if (!interactive && listenerStarted) {
                    Log("Interaction gate closed: credential input is no longer visible.");
                    StopBackgroundListener();
                    listenerStarted = false;
                    SetStatusForSid(sid, L"进入登录界面后，Living就来找你的手机。");
                }

                // No periodic desktop polling when the OS event hook is available.
                const DWORD timeout = hook && interactionGateStop_ && !desktopName.empty() ? INFINITE : 1000;
                const auto wake = MsgWaitForMultipleObjectsEx(interactionGateStop_ ? 1 : 0,
                    interactionGateStop_ ? &interactionGateStop_ : nullptr, timeout,
                    QS_ALLINPUT, MWMO_INPUTAVAILABLE);
                if (wake == WAIT_FAILED) break;
                MSG message{};
                while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
                    if (message.message == WM_QUIT) interactionGateRunning_ = false;
                    TranslateMessage(&message);
                    DispatchMessageW(&message);
                }
            }

            if (hook) UnhookWinEvent(hook);
            interactionGateRunning_ = false;
            if (listenerStarted || bgRunning_.load()) StopBackgroundListener();
            Log("Interaction gate stopped for SID %ls", sid.c_str());
        });
    }

    void WaitForRetry(std::uint64_t completedGeneration) {
        Log("Background: Pausing and waiting for manual retry from PC UI...");
        bgBle_.Stop();
        AbortActiveClientSocket();
        // Keep Winsock alive until the worker releases its accepted client.
        bgServer_.StopListening();
        {
            std::unique_lock<std::mutex> lock(retryMutex_);
            waitingForRetry_ = true;
            retryCv_.wait(lock, [this, completedGeneration] {
                return !bgRunning_ || retryGeneration_.load() != completedGeneration;
            });
            waitingForRetry_ = false;
        }
        Log("Background: Resumed from manual retry or stop (bgRunning=%d)", bgRunning_.load());
    }

    void SetStatusForSid(const std::wstring& sid, const std::wstring& status) {
        Log("Provider::SetStatusForSid (%ls): %ls", sid.c_str(), status.c_str());
        std::lock_guard<std::mutex> lock(credentialsMutex_);
        for (auto* c : credentials_) {
            if (c && (sid.empty() || c->GetSid() == sid)) {
                c->SetStatusText(status);
            }
        }
    }

    void SetStatusForAll(const std::wstring& status) {
        SetStatusForSid(L"", status);
    }

    void AbortActiveClientSocket() {
        std::lock_guard<std::mutex> lock(activeSocketMutex_);
        if (activeClientSocket_ != INVALID_SOCKET) {
            Log("AbortActiveClientSocket: closing active socket %zu", activeClientSocket_);
            // Wake the receiver without blocking LogonUI on a Bluetooth send.
            // The worker owns closing the socket, avoiding handle reuse races.
            shutdown(activeClientSocket_, SD_BOTH);
        }
    }

    void Clear() noexcept {
        std::lock_guard<std::mutex> lock(credentialsMutex_);
        for (auto* c : credentials_) c->Release();
        credentials_.clear();
    }

    void Enumerate() {
        Clear();
        if (!enabled_ || !users_) { dirty_ = false; return; }
        DWORD count = 0;
        if (FAILED(users_->GetCount(&count))) return;
        Log("Provider::Enumerate: %lu users found in LogonUI user array", count);

        std::vector<std::wstring> pairedSids;
        std::map<std::wstring, DWORD> tileIndices;
        std::vector<Credential*> newCredentials;

        for (DWORD i = 0; i < count; ++i) {
            ICredentialProviderUser* user = nullptr;
            if (FAILED(users_->GetAt(i, &user))) continue;
            PWSTR sid = nullptr; PWSTR name = nullptr;
            const auto sidResult = user->GetSid(&sid);
            user->GetStringValue(PKEY_Identity_QualifiedUserName, &name);
            user->Release();
            Log("  User %lu: sidResult=0x%08X, sid=%ls, name=%ls",
                i, sidResult, sid ? sid : L"(null)", name ? name : L"(null)");
            try {
                const bool authenticatorEnabled = SUCCEEDED(sidResult) && sid && lockpin::IsEnrolled(sid);
                const bool phoneEnabled = SUCCEEDED(sidResult) && sid && lockpin::phone::IsPhonePaired(sid);
                if (authenticatorEnabled || phoneEnabled) {
                    Log("  -> Matched enrolled/paired user %ls! Creating credential tile.", sid);
                    const DWORD tileIdx = static_cast<DWORD>(newCredentials.size());
                    auto* c = new Credential(this, sid, name ? name : L"",
                        authenticatorEnabled, phoneEnabled);
                    try { newCredentials.push_back(c); } catch (...) { c->Release(); throw; }

                    if (phoneEnabled) {
                        pairedSids.push_back(sid);
                        tileIndices[sid] = tileIdx;
                    }
                }
            } catch (...) { CoTaskMemFree(sid); CoTaskMemFree(name); throw; }
            CoTaskMemFree(sid); CoTaskMemFree(name);
        }
        dirty_ = false;

        {
            std::lock_guard<std::mutex> lock(pairedSidsMutex_);
            pairedSids_ = std::move(pairedSids);
            sidTileIndices_ = std::move(tileIndices);
        }
        {
            std::lock_guard<std::mutex> lock(credentialsMutex_);
            credentials_ = std::move(newCredentials);
        }

        Log("Provider::Enumerate: %zu credential tiles created (%zu paired for phone unlock)",
            credentials_.size(), pairedSids_.size());
#ifndef LOCKPIN_TESTING
        try {
            for(const auto& sid:pairedSids_) {
                if(const auto cached=lockpin::phone::ReadSharedApplicationTime(sid)) {
                    applicationTime_.clock.Apply(*cached,lockpin::phone::TimeTickMs());break;
                }
            }
            applicationTime_.Start();
        } catch (...) { Log("Application time startup unavailable; retaining the system-seeded internal clock"); }
#endif
    }

    void StartBackgroundListener() {
        std::lock_guard<std::mutex> lock(backgroundMutex_);
        StopBackgroundListenerLocked();
        if (!backgroundWake_) {
            SetStatusForAll(L"无法创建蓝牙等待事件，请先使用 PIN 登录。");
            return;
        }
        bgRunning_ = true;
        bgThread_ = std::thread(&Provider::BackgroundListenerProc, this);
    }

    void StopBackgroundListener() {
        std::lock_guard<std::mutex> lock(backgroundMutex_);
        StopBackgroundListenerLocked();
    }

    void StopBackgroundListenerLocked() {
        if (bgRunning_.exchange(false)) {
            Log("Stopping background RFCOMM listener...");
            AbortActiveClientSocket();
            if (backgroundWake_) SetEvent(backgroundWake_);
            autoUnlock_.cv.notify_all();
            {
                std::lock_guard<std::mutex> lock(retryMutex_);
            }
            retryCv_.notify_all();
            if (bgThread_.joinable()) {
                bgThread_.join();
            }
            // Start/refresh runs on bgThread_; stop only after it can no longer publish.
            bgBle_.Stop();
            bgServer_.Stop();
            Log("Background RFCOMM listener stopped.");
        }
    }

    SOCKET AwaitPhoneConnection(lockpin::phone::BluetoothRadioWatcher& radio,
        std::uint64_t generation) {
        using lockpin::phone::ConnectionWindow;
        bgBle_.Stop();
        bgServer_.Stop();
        ConnectionWindow window;
        bool available = radio.Refresh();
        bool listening = false;
        bool legacyFallback = false;
        std::uint64_t fallbackDeadline = 0;
        std::wstring lastStatus;
        const auto status = [&](const std::wstring& text) {
            if (text != lastStatus) { SetStatusForAll(text); lastStatus = text; }
        };
        const auto cancelled = [&] {
            return !bgRunning_ || generation != retryGeneration_.load();
        };

        while (!cancelled()) {
            auto now = GetTickCount64();
            if (window.Expired(now) || (fallbackDeadline && now >= fallbackDeadline)) {
                status(L"本次连接等待已结束，需要时请点“再次发送”。");
                return INVALID_SOCKET;
            }
            if (!available) {
                status(L"蓝牙尚未就绪，等待设备可用后连接手机。");
                if (!radio.ChangeEvent()) {
                    status(L"无法接收蓝牙设备通知，请等蓝牙开启后点“再次发送”。");
                    return INVALID_SOCKET;
                }
            } else {
                window.Start(now);
                // Give an already queued legacy connection priority over a retry.
                if (listening && WaitForSingleObject(bgServer_.ConnectionEvent(), 0) == WAIT_OBJECT_0) {
                    const SOCKET incoming = bgServer_.Accept(0);
                    if (incoming != INVALID_SOCKET) return incoming;
                }
                if (window.CanAttempt(now)) {
                    const auto timeout = window.BeginAttempt(now);
                    Log("Background: connection setup attempt %u/%u", window.Attempts(), ConnectionWindow::MaxAttempts);
                    status(L"正在连接手机（第 " + std::to_wstring(window.Attempts()) + L"/" +
                        std::to_wstring(ConnectionWindow::MaxAttempts) + L" 次）...");
                    if (!listening) listening = bgServer_.Start(L"WindowsLockPin Unlock");
                    if (!listening) {
                        Log("Background: Bluetooth service not ready; using bounded backoff");
                        window.Failed(GetTickCount64());
                    } else {
                        std::uint64_t phoneAddress = 0;
                        {
                            std::lock_guard<std::mutex> lock(pairedSidsMutex_);
                            for (const auto& sid : pairedSids_) {
                                const auto record = lockpin::phone::LoadPairedPhone(sid);
                                if (record) { phoneAddress = record->phoneBluetoothAddress; break; }
                            }
                        }
                        if (!phoneAddress) {
                            // Only legacy records use BLE. Known routes never broadcast
                            // or require the phone to scan when a connection fails.
                            window.StopAttempts();
                            legacyFallback = true;
                            fallbackDeadline = GetTickCount64() +
                                (std::min)(window.RemainingMs(GetTickCount64()), ConnectionWindow::LegacyFallbackMs);
                            Log("Background: legacy pairing; enabling bounded BLE fallback");
                            if (!bgBle_.Start(bgServer_.LocalBluetoothAddress())) {
                                status(L"蓝牙兼容广播未能启动，请检查蓝牙后点“再次发送”。");
                                return INVALID_SOCKET;
                            }
                        } else {
                            // Setup time is included in the overall connection deadline.
                            const auto remaining = window.RemainingMs(GetTickCount64());
                            if (!remaining || cancelled()) return INVALID_SOCKET;
                            const SOCKET client = lockpin::phone::ConnectPairedPhone(phoneAddress,
                                (std::min)(timeout, remaining), cancelled, backgroundWake_);
                            if (cancelled()) {
                                if (client != INVALID_SOCKET) closesocket(client);
                                return INVALID_SOCKET;
                            }
                            if (client != INVALID_SOCKET) return client;
                            window.Failed(GetTickCount64());
                            Log("Background: direct connection failed; attempt=%u", window.Attempts());
                        }
                    }
                }
            }
            if (cancelled()) return INVALID_SOCKET;
            now = GetTickCount64();
            if (window.Expired(now) || (fallbackDeadline && now >= fallbackDeadline)) continue;
            if (available && !legacyFallback && !window.HasPendingAttempt()) {
                status(L"暂时没连上手机，请确认手机解锁监听已开启，再点“再次发送”。");
                return INVALID_SOCKET;
            }

            DWORD waitMs = available ? window.WaitMs(now) : window.RemainingMs(now);
            if (legacyFallback) {
                const auto remaining = static_cast<DWORD>(fallbackDeadline - now);
                status(L"等待旧配对手机回连，还剩 " + std::to_wstring(ConnectionWindow::CountdownSeconds(remaining)) + L" 秒。");
                waitMs = (std::min)(waitMs, remaining);
                waitMs = (std::min)(waitMs, DWORD{1000});
            } else if (available && window.Attempts() > 0 && waitMs > 0) {
                status(L"手机还没连上，" + std::to_wstring(ConnectionWindow::CountdownSeconds(waitMs)) +
                    L" 秒后重试（第 " + std::to_wstring(window.Attempts() + 1) + L"/" +
                    std::to_wstring(ConnectionWindow::MaxAttempts) + L" 次）。");
                // The UI countdown is the only periodic wake during backoff.
                // No timer runs while awaiting radio availability or manual retry.
                waitMs = (std::min)(waitMs, DWORD{1000});
            }
            HANDLE handles[3] = {backgroundWake_};
            DWORD count = 1;
            DWORD radioIndex = MAXDWORD;
            if (radio.ChangeEvent()) { radioIndex = count; handles[count++] = radio.ChangeEvent(); }
            if (listening) handles[count++] = bgServer_.ConnectionEvent();
            const auto result = WaitForMultipleObjects(count, handles, FALSE, waitMs);
            if (result == WAIT_FAILED) {
                Log("Background: connection event wait failed: %lu", GetLastError());
                status(L"蓝牙等待失败，请点“再次发送”。");
                return INVALID_SOCKET;
            }
            if (radioIndex != MAXDWORD && result == WAIT_OBJECT_0 + radioIndex) {
                available = radio.Refresh();
                if (!available && window.Started()) {
                    // A removed adapter invalidates the current listening socket.
                    // End this request instead of reusing it or extending its budget.
                    status(L"蓝牙设备已断开，请恢复蓝牙后点“再次发送”。");
                    return INVALID_SOCKET;
                }
            }
        }
        return INVALID_SOCKET;
    }

    void BackgroundListenerProc() {
        Log("Background RFCOMM listener thread started.");
        SetStatusForAll(L"手机呢...让我找找");

        lockpin::phone::BluetoothRadioWatcher radio;
        if (!radio.Start()) Log("Background: radio notification registration unavailable");

        while (bgRunning_) {
            const auto generation = retryGeneration_.load();
            while (bgRunning_) {
                const SOCKET client = AwaitPhoneConnection(radio, generation);
                if (client == INVALID_SOCKET) {
                    if (bgRunning_ && generation == retryGeneration_.load()) WaitForRetry(generation);
                    break;
                }
                if (!bgRunning_ || generation != retryGeneration_.load()) {
                    closesocket(client);
                    break;
                }
                Log("Background: Companion phone connected! Socket: %zu", client);
                // Phone has connected — stop the BLE beacon to save power
                bgBle_.Stop();
                Log("BLE beacon stopped (phone is connected).");
                {
                    std::lock_guard<std::mutex> lock(activeSocketMutex_);
                    activeClientSocket_ = client;
                }

                struct SocketCloser {
                    Provider& p;
                    SOCKET s;
                    ~SocketCloser() {
                        std::lock_guard<std::mutex> lock(p.activeSocketMutex_);
                        if (p.activeClientSocket_ == s) {
                            closesocket(s);
                            p.activeClientSocket_ = INVALID_SOCKET;
                        }
                    }
                } closer{*this, client};

                std::wstring targetSid;
                DWORD targetTile = 0;
                std::optional<lockpin::phone::PairedDeviceRecord> phoneOpt;

                {
                    std::lock_guard<std::mutex> lock(pairedSidsMutex_);
                    for (const auto& s : pairedSids_) {
                        auto opt = lockpin::phone::LoadPairedPhone(s);
                        if (opt.has_value()) {
                            phoneOpt.emplace(std::move(*opt));
                            targetSid = s;
                            auto it = sidTileIndices_.find(s);
                            if (it != sidTileIndices_.end()) targetTile = it->second;
                            break;
                        }
                    }
                }

                if (!phoneOpt.has_value() || targetSid.empty()) {
                    Log("Background: No paired phone record found for any current user.");
                    SetStatusForAll(L"不是这个手机！用PIN登录配对去！");
                    WaitForRetry(generation);
                    break;
                }

                auto& phoneRecord = *phoneOpt;
                Log("Background: Paired phone loaded: %s (%s), PC ID: %s, for SID %ls",
                    phoneRecord.deviceName, phoneRecord.deviceId, phoneRecord.pcId, targetSid.c_str());

                SetStatusForSid(targetSid, L"手机呢...让我找找");

                lockpin::phone::BcryptRandomSource random;
                std::vector<std::uint8_t> nonce(32);
                if (!random.Fill(nonce.data(), nonce.size())) {
                    Log("Background: Failed to generate random nonce");
                    SetStatusForSid(targetSid, L"刚刚出了点问题...点“再次发送”让我再试试");
                    WaitForRetry(generation);
                    break;
                }

                std::array<std::uint8_t, 8> requestIdBytes{};
                if (!random.Fill(requestIdBytes.data(), requestIdBytes.size())) {
                    Log("Background: Failed to generate random request ID");
                    SetStatusForSid(targetSid, L"刚刚出了点问题...点“再次发送”让我再试试");
                    WaitForRetry(generation);
                    break;
                }
                std::uint64_t requestId = 0;
                for (const auto byte : requestIdBytes) requestId = (requestId << 8) | byte;
                if (requestId == 0) requestId = 1;
                {
                    std::lock_guard<std::mutex> lock(autoUnlock_.mutex);
                    if (generation != retryGeneration_.load()) continue;
                    activeRequestId_.store(requestId);
                }

                const auto sendUnlockResult = [&](std::uint16_t statusCode, const char* resultMessage) noexcept {
                    try {
                        lockpin::phone::UnlockResultPayload resultPayload{statusCode, resultMessage ? resultMessage : ""};
                        lockpin::phone::Frame resultFrame{
                            lockpin::phone::MessageType::UnlockResult,
                            requestId,
                            lockpin::phone::EncodeUnlockResult(resultPayload)
                        };
                        const bool sent = lockpin::phone::SendAll(client, lockpin::phone::EncodeFrame(resultFrame));
                        Log("Background: UNLOCK_RESULT status=%u sent=%s", static_cast<unsigned>(statusCode), sent ? "true" : "false");
                        return sent;
                    } catch (...) {
                        Log("Background: Failed to encode/send UNLOCK_RESULT");
                        return false;
                    }
                };

                lockpin::phone::FrameStreamReader timeFrameReader;
                {
                    if(const auto cached=lockpin::phone::ReadSharedApplicationTime(targetSid))
                        applicationTime_.clock.Apply(*cached,lockpin::phone::TimeTickMs());
                    std::vector<std::uint8_t> timeKey(std::begin(phoneRecord.kPair),std::end(phoneRecord.kPair));
                    const auto cancelled=[&]{return !bgRunning_ || generation!=retryGeneration_.load();};
                    const bool proceed=lockpin::phone::RunTimeHandshake(client,applicationTime_,timeKey,requestId,nonce,
                        cancelled,[&](const wchar_t* text){SetStatusForSid(targetSid,text);},{},&timeFrameReader);
                    SecureZeroMemory(timeKey.data(),timeKey.size());
                    lockpin::phone::WriteSharedApplicationTime(targetSid,applicationTime_.clock.Snapshot(lockpin::phone::TimeTickMs()));
                    if(!proceed) { WaitForRetry(generation);break; }
                }
                const std::uint64_t timestampMs = applicationTime_.clock.Snapshot(lockpin::phone::TimeTickMs()).utcMs;
                const std::uint32_t ttlMs = 30000;
                const auto challengeDeadlineTick = GetTickCount64() + ttlMs;

                lockpin::phone::UnlockChallengePayload challenge;
                challenge.pcId = phoneRecord.pcId;
                challenge.nonce = nonce;
                challenge.timestampMs = timestampMs;
                challenge.ttlMs = ttlMs;
                lockpin::Record displayRecord;
                if(lockpin::LoadEnrolledCredentials(targetSid,displayRecord)) {
                    std::wstring name=displayRecord.username;
                    const std::wstring prefix=L"MicrosoftAccount\\";
                    if(name.compare(0,prefix.size(),prefix)==0)name.erase(0,prefix.size());
                    challenge.userDisplayName=lockpin::phone::InfoUtf8(name);
                }
                if(challenge.userDisplayName.empty()) {
                    std::lock_guard<std::mutex> lock(credentialsMutex_);
                    for(auto* c:credentials_)if(c && c->GetSid()==targetSid)challenge.userDisplayName=c->GetDisplayUsername();
                }
                if(challenge.userDisplayName.empty())challenge.userDisplayName="Windows";
                if(challenge.userDisplayName.size()>64) {
                    size_t end=64;
                    while(end>0 && (static_cast<unsigned char>(challenge.userDisplayName[end])&0xC0)==0x80)--end;
                    challenge.userDisplayName.resize(end);
                }
                try {
                    auto payload=lockpin::phone::EncryptDeviceInfo(lockpin::phone::BuildDeviceInfo(phoneRecord.pcId,targetSid),
                        std::vector<std::uint8_t>(std::begin(phoneRecord.kPair),std::end(phoneRecord.kPair)));
                    lockpin::phone::SendAll(client,lockpin::phone::EncodeFrame({lockpin::phone::MessageType::Pong,requestId,std::move(payload)}));
                } catch (...) { Log("Optional device metadata unavailable"); }

                const auto challengePayload = lockpin::phone::EncodeUnlockChallenge(challenge);
                lockpin::phone::Frame challengeFrame{
                    lockpin::phone::MessageType::UnlockChallenge,
                    requestId,
                    challengePayload
                };

                SetStatusForSid(targetSid, L"噢连上了，等下让我看看...");

                if (!lockpin::phone::SendAll(client, lockpin::phone::EncodeFrame(challengeFrame))) {
                    Log("Background: SendAll challenge frame failed");
                    SetStatusForSid(targetSid, L"咦，请求没发过去...再发一次看看");
                    WaitForRetry(generation);
                    break;
                }
                Log("Background: UNLOCK_CHALLENGE sent. Waiting for UNLOCK_RESPONSE from phone...");
                SetStatusForSid(targetSid, L"按一下指纹Living就让你进桌面( •̀ ω •́ )y~");

                std::vector<std::uint8_t> wire;
                if (!lockpin::phone::ReceiveUnlockFrameUntil(client, wire, challengeDeadlineTick,&timeFrameReader)) {
                    Log("Background: ReceiveFrame timed out or failed");
                    SetStatusForSid(targetSid, GetTickCount64() >= challengeDeadlineTick
                        ? L"请求超时了，再给Living我发一遍"
                        : L"刚还连着呢，怎么断了...让我再找找");
                    WaitForRetry(generation);
                    break;
                }

                const auto frameResult = lockpin::phone::DecodeFrame(wire.data(), wire.size());
                if (!frameResult.frame) {
                    Log("Background: DecodeFrame failed");
                    sendUnlockResult(1, "Malformed unlock response");
                    SetStatusForSid(targetSid, L"是这个手机吗...再发一次请求看看");
                    WaitForRetry(generation);
                    break;
                }

                if (frameResult.frame->type == lockpin::phone::MessageType::Cancel) {
                    Log("Background: User cancelled biometric prompt on phone");
                    SetStatusForSid(targetSid, L"好的，我就在这里等你");
                    WaitForRetry(generation);
                    break;
                }

                if (frameResult.frame->type != lockpin::phone::MessageType::UnlockResponse ||
                    frameResult.frame->requestId != requestId) {
                    Log("Background: Unexpected frame or requestId mismatch");
                    sendUnlockResult(1, "Invalid response");
                    SetStatusForSid(targetSid, L"是这个手机吗...再发一次请求看看");
                    WaitForRetry(generation);
                    break;
                }

                auto responseOpt = lockpin::phone::DecodeUnlockResponse(
                    frameResult.frame->payload.data(), frameResult.frame->payload.size());
                if (!responseOpt || responseOpt->statusCode != 0 || responseOpt->nonce != challenge.nonce) {
                    Log("Background: Unlock response invalid or rejected");
                    sendUnlockResult(1, "Unlock rejected or nonce mismatch");
                    SetStatusForSid(targetSid, L"是这个手机吗...再发一次请求看看");
                    WaitForRetry(generation);
                    break;
                }

                const auto transcript = lockpin::phone::BuildUnlockTranscript(
                    challenge.pcId,
                    phoneRecord.deviceId,
                    challengeFrame.requestId,
                    challenge.nonce,
                    challenge.timestampMs,
                    challenge.ttlMs
                );

                const std::vector<std::uint8_t> pubKey(phoneRecord.clientPublicKey, phoneRecord.clientPublicKey + 65);
                const bool verified = lockpin::phone::VerifyP256Signature(
                    pubKey, transcript, responseOpt->signature);
                if (!verified) {
                    Log("Background: >>> P-256 SIGNATURE VERIFICATION FAILED! <<<");
                    sendUnlockResult(1, "Biometric signature rejected");
                    SetStatusForSid(targetSid, L"是这个手机吗...再发一次请求看看");
                    WaitForRetry(generation);
                    break;
                }

                Log("Background: >>> BIOMETRIC SIGNATURE VERIFIED SUCCESSFULLY! <<<");
                SetStatusForSid(targetSid, L"验证完毕，随我进来吧");

                Log("Background: Loading enrolled credentials for SID %ls...", targetSid.c_str());
                lockpin::Record record;
                if (!lockpin::LoadEnrolledCredentials(targetSid, record)) {
                    Log("Background: LoadEnrolledCredentials FAILED for SID %ls", targetSid.c_str());
                    sendUnlockResult(2, "Windows credential is unavailable");
                    SetStatusForSid(targetSid, L"保存的账户凭据不见了...先用PIN进桌面重新保存一下");
                    WaitForRetry(generation);
                    break;
                }

                Log("Background: Loaded credentials! Username: %ls. Packing identity...", record.username);
                CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION packed{};
                const auto hr = lockpin::PackIdentity(record.username, record.password, &packed);
                if (FAILED(hr)) {
                    Log("Background: PackIdentity FAILED: 0x%08X", hr);
                    sendUnlockResult(2, "Windows could not prepare the credential");
                    SetStatusForSid(targetSid, L"登录凭据没准备好...先用PIN进桌面看看");
                    WaitForRetry(generation);
                    break;
                }

                Log("Background: PackIdentity SUCCEEDED! Preparing auto-unlock serialization...");
                {
                    std::lock_guard<std::mutex> lock(autoUnlock_.mutex);
                    if (generation != retryGeneration_.load() || activeRequestId_.load() != requestId ||
                        GetTickCount64() >= challengeDeadlineTick) {
                        SecureZeroMemory(packed.rgbSerialization, packed.cbSerialization);
                        CoTaskMemFree(packed.rgbSerialization);
                        continue;
                    }
                    autoUnlock_.targetSid = targetSid;
                    autoUnlock_.serializedData.assign(
                        packed.rgbSerialization, packed.rgbSerialization + packed.cbSerialization);
                    autoUnlock_.providerClsid = packed.clsidCredentialProvider;
                    autoUnlock_.targetTileIndex = targetTile;
                    autoUnlock_.ready = true;
                }
                SecureZeroMemory(packed.rgbSerialization, packed.cbSerialization);
                CoTaskMemFree(packed.rgbSerialization);

                // Learn legacy routing only after signature, request generation and TTL checks.
                // Bluetooth names/nearby-device discovery are never trusted as pairing identity.
                const auto peerAddress = lockpin::phone::PeerBluetoothAddress(client);
                if (peerAddress && peerAddress != phoneRecord.phoneBluetoothAddress) {
                    phoneRecord.version = 2;
                    phoneRecord.phoneBluetoothAddress = peerAddress;
                    try {
                        lockpin::phone::SavePairedPhone(phoneRecord);
                        Log("Background: authenticated phone routing saved");
                    } catch (...) { Log("Background: phone routing save failed; BLE fallback remains available"); }
                }
                sendUnlockResult(0, "Windows accepted the unlock request");

                autoUnlock_.cv.notify_all();

                if (events_) {
                    Log("Background: Calling events_->CredentialsChanged(%p)...", (void*)upAdviseContext_);
                    events_->CredentialsChanged(upAdviseContext_);
                }
                break;
            }

            // If auto-unlock is ready, wait until it is consumed or provider is stopped
            {
                std::unique_lock<std::mutex> lock(autoUnlock_.mutex);
                autoUnlock_.cv.wait(lock, [this] { return !bgRunning_ || !autoUnlock_.ready; });
            }
            // Consuming a successful credential is not a new request. Wait for
            // deselection/stop or explicit resend instead of starting a new window.
            if (bgRunning_ && generation == retryGeneration_.load()) WaitForRetry(generation);
        }
        Log("Background RFCOMM listener thread exited.");
    }

public:
    Provider() {
        InterlockedIncrement(&moduleReferences);
        Log("Provider constructed");
    }

#ifdef LOCKPIN_TESTING
    bool TestRetryBeforeWait() {
        bgRunning_ = true;
        for (int i = 0; i < 20; ++i) TriggerRetry(L"test");
        const bool invalidated = retryGeneration_ == 20 && activeRequestId_ == 0;
        // A retry delivered before WaitForRetry must remain pending.
        std::atomic<bool> watchdogFired{false};
        std::atomic<bool> done{false};
        std::thread watchdog([&] {
            for (int i = 0; i < 100 && !done; ++i)
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
            if (!done) { watchdogFired = true; bgRunning_ = false; retryCv_.notify_all(); }
        });
        WaitForRetry(0);
        done = true;
        watchdog.join();
        bgRunning_ = false;
        return invalidated && !watchdogFired;
    }

    bool TestRetryWaitGeneration() {
        bgRunning_ = true;
        TriggerRetry(L"test");
        const auto generation = retryGeneration_.load();
        std::atomic<bool> done{false};
        std::atomic<bool> enteredWait{false};
        std::atomic<bool> wokeEarly{false};
        std::thread resender([&] {
            for (int i = 0; i < 100 && !waitingForRetry_; ++i)
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
            enteredWait = waitingForRetry_.load();
            std::this_thread::sleep_for(std::chrono::milliseconds(25));
            wokeEarly = done.load();
            TriggerRetry(L"test");
        });
        // A retry already used by this request must not revive it after timeout.
        WaitForRetry(generation);
        done = true;
        resender.join();
        bgRunning_ = false;
        return enteredWait && !wokeEarly;
    }
#endif

    ~Provider() {
        Log("Provider destructing");
        StopInteractionGate();
        StopBackgroundListener();
        applicationTime_.Stop();
        Clear();
        if (users_) users_->Release();
        if (backgroundWake_) CloseHandle(backgroundWake_);
        if (interactionGateStop_) CloseHandle(interactionGateStop_);
        InterlockedDecrement(&moduleReferences);
    }

    void OnCredentialSelected(const std::wstring& sid) {
        Log("Provider::OnCredentialSelected for SID %ls; waiting for interactive Winlogon desktop", sid.c_str());
        if (!lockpin::phone::IsPhonePaired(sid)) return;
        SetStatusForSid(sid, L"进入登录界面后，Living就来找你的手机。");
        StartInteractionGate(sid);
    }

    void OnCredentialDeselected(const std::wstring& sid) {
        Log("Provider::OnCredentialDeselected for SID %ls (exiting credential stage)", sid.c_str());
        StopInteractionGate();
        StopBackgroundListener();
    }

    void TriggerRetry(const std::wstring& sid) {
        Log("Provider::TriggerRetry requested for SID %ls", sid.c_str());
        SetStatusForSid(sid, L"再发一次，Living这就去找手机...");
        {
            std::lock_guard<std::mutex> lock(autoUnlock_.mutex);
            ++retryGeneration_;
            activeRequestId_ = 0;
            autoUnlock_.ready = false;
            SecureZeroMemory(autoUnlock_.serializedData.data(), autoUnlock_.serializedData.size());
            autoUnlock_.serializedData.clear();
        }
        autoUnlock_.cv.notify_all();

        // Wake background thread from WaitForRetry if it was paused
        {
            std::lock_guard<std::mutex> lock(retryMutex_);
        }
        retryCv_.notify_all();
        if (backgroundWake_) SetEvent(backgroundWake_);
        AbortActiveClientSocket();

        if (!bgRunning_) {
            StartBackgroundListener();
        }
    }

    bool ConsumeAutoUnlock(const std::wstring& sid, CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* packed) {
        std::lock_guard<std::mutex> lock(autoUnlock_.mutex);
        if (autoUnlock_.ready && autoUnlock_.targetSid == sid && !autoUnlock_.serializedData.empty()) {
            packed->cbSerialization = static_cast<ULONG>(autoUnlock_.serializedData.size());
            packed->rgbSerialization = static_cast<BYTE*>(CoTaskMemAlloc(packed->cbSerialization));
            if (!packed->rgbSerialization) return false;
            memcpy(packed->rgbSerialization, autoUnlock_.serializedData.data(), packed->cbSerialization);
            packed->clsidCredentialProvider = autoUnlock_.providerClsid;

            SecureZeroMemory(autoUnlock_.serializedData.data(), autoUnlock_.serializedData.size());
            autoUnlock_.serializedData.clear();
            autoUnlock_.ready = false;
            autoUnlock_.cv.notify_all();
            return true;
        }
        return false;
    }

    AutoUnlockState& GetAutoUnlockState() noexcept { return autoUnlock_; }

    IFACEMETHODIMP QueryInterface(REFIID iid, void** result) override {
        if (!result) return E_POINTER;
        *result = nullptr;
        if (iid == IID_IUnknown || iid == IID_ICredentialProvider) *result = static_cast<ICredentialProvider*>(this);
        else if (iid == IID_ICredentialProviderSetUserArray) *result = static_cast<ICredentialProviderSetUserArray*>(this);
        if (!*result) return E_NOINTERFACE;
        AddRef(); return S_OK;
    }

    IFACEMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&references_); }
    IFACEMETHODIMP_(ULONG) Release() override {
        const auto n = InterlockedDecrement(&references_); if (!n) delete this; return n;
    }

    IFACEMETHODIMP SetUsageScenario(CREDENTIAL_PROVIDER_USAGE_SCENARIO scenario, DWORD) override {
        Log("Provider::SetUsageScenario: %d", scenario);
        enabled_ = (scenario == CPUS_LOGON || scenario == CPUS_UNLOCK_WORKSTATION) && !GetSystemMetrics(SM_REMOTESESSION);
        dirty_ = true;
        StopInteractionGate();
        StopBackgroundListener();
        Clear();
        return enabled_ ? S_OK : E_NOTIMPL;
    }

    IFACEMETHODIMP SetSerialization(const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION*) override { return E_NOTIMPL; }

    IFACEMETHODIMP Advise(ICredentialProviderEvents* events, UINT_PTR upAdviseContext) override {
        Log("Provider::Advise called: events=%p, context=%p", (void*)events, (void*)upAdviseContext);
        if (events) events->AddRef();
        if (events_) events_->Release();
        events_ = events;
        upAdviseContext_ = upAdviseContext;
        return S_OK;
    }

    IFACEMETHODIMP UnAdvise() override {
        Log("Provider::UnAdvise called");
        StopInteractionGate();
        StopBackgroundListener();
        if (events_) { events_->Release(); events_ = nullptr; }
        upAdviseContext_ = 0;
        return S_OK;
    }

    IFACEMETHODIMP SetUserArray(ICredentialProviderUserArray* users) override {
        Log("Provider::SetUserArray called");
        if (users) users->AddRef();
        if (users_) users_->Release();
        users_ = users; dirty_ = true; Clear(); return S_OK;
    }

    IFACEMETHODIMP GetFieldDescriptorCount(DWORD* count) override { *count = FieldCount; return S_OK; }
    IFACEMETHODIMP GetFieldDescriptorAt(DWORD index, CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** result) override {
        *result = nullptr;
        if (index >= FieldCount) return E_INVALIDARG;
        auto descriptor = static_cast<CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR*>(CoTaskMemAlloc(sizeof(CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR)));
        if (!descriptor) return E_OUTOFMEMORY;
        *descriptor = {}; descriptor->dwFieldID = index; descriptor->cpft = types[index];
        if (index == Icon) descriptor->guidFieldType = CPFG_CREDENTIAL_PROVIDER_LOGO;
        if (index == Title) descriptor->guidFieldType = CPFG_CREDENTIAL_PROVIDER_LABEL;
        const auto hr = SHStrDupW(labels[index], &descriptor->pszLabel);
        if (FAILED(hr)) { CoTaskMemFree(descriptor); return hr; }
        *result = descriptor; return S_OK;
    }

    IFACEMETHODIMP GetCredentialCount(DWORD* count, DWORD* defaultIndex, BOOL* automatic) override {
        *count = 0; *defaultIndex = CREDENTIAL_PROVIDER_NO_DEFAULT; *automatic = FALSE;
        try {
            if (dirty_) Enumerate();
            std::lock_guard<std::mutex> lock(credentialsMutex_);
            *count = static_cast<DWORD>(credentials_.size());
            Log("GetCredentialCount: returning %lu tiles", *count);

            std::lock_guard<std::mutex> lockAuto(autoUnlock_.mutex);
            if (autoUnlock_.ready && autoUnlock_.targetTileIndex < *count) {
                Log("GetCredentialCount: auto-unlock is READY for tile %lu! Setting automatic=TRUE",
                    autoUnlock_.targetTileIndex);
                *defaultIndex = autoUnlock_.targetTileIndex;
                *automatic = TRUE;
            }
            return S_OK;
        } catch (...) { Clear(); return S_OK; }
    }

    IFACEMETHODIMP GetCredentialAt(DWORD index, ICredentialProviderCredential** result) override {
        *result = nullptr;
        std::lock_guard<std::mutex> lock(credentialsMutex_);
        if (index >= credentials_.size()) return E_INVALIDARG;
        *result = credentials_[index]; (*result)->AddRef(); return S_OK;
    }
};

IFACEMETHODIMP Credential::SetSelected(BOOL* automatic) {
    Log("Credential::SetSelected for SID %ls (entering password/PIN stage)", sid_.c_str());
    if (automatic) *automatic = FALSE;
    if (provider_ && phoneEnabled_) {
        provider_->OnCredentialSelected(sid_);
    }
    return S_OK;
}

IFACEMETHODIMP Credential::SetDeselected() {
    Log("Credential::SetDeselected for SID %ls (exiting credential stage)", sid_.c_str());
    Clear();
    if (provider_) {
        provider_->OnCredentialDeselected(sid_);
    }
    return S_OK;
}

IFACEMETHODIMP Credential::CommandLinkClicked(DWORD field) {
    Log("Credential::CommandLinkClicked: field = %lu, SID = %ls", field, sid_.c_str());
    if (field == Retry && phoneEnabled_) {
        if (provider_) {
            provider_->TriggerRetry(sid_);
        } else {
            SetStatusText(L"再发一次，Living这就去找手机...");
        }
        return S_OK;
    }
    return E_NOTIMPL;
}

IFACEMETHODIMP Credential::GetSerialization(CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* response,
    CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* packed, PWSTR* message,
    CREDENTIAL_PROVIDER_STATUS_ICON* icon) {
    Log("Credential::GetSerialization invoked for SID %ls", sid_.c_str());
    *response = CPGSR_NO_CREDENTIAL_NOT_FINISHED; *packed = {}; *message = nullptr; *icon = CPSI_ERROR;
    const wchar_t* error = L"Living这边暂时没准备好，先用PIN进桌面吧";
    try {
        // 1. Check if background auto-unlock has already completed:
        if (provider_ && provider_->ConsumeAutoUnlock(sid_, packed)) {
            Log("GetSerialization: Consumed pre-authenticated auto-unlock credential!");
            SetStatusText(L"验证完毕，随我进来吧");
            *response = CPGSR_RETURN_CREDENTIAL_FINISHED;
            *icon = CPSI_NONE;
            return S_OK;
        }

        std::array<char, 7> code{};
        const auto length = wcslen(code_.data());
        for (std::size_t i = 0; i < length; ++i) code[i] = static_cast<char>(code_[i]);
        Clear();

        // 2. If 6-digit TOTP code was entered:
        if (length == 6 && authenticatorEnabled_) {
            Log("Attempting TOTP verification for SID %ls...", sid_.c_str());
            SetStatusText(L"收到了，等我核对一下...");
            lockpin::Record record;
            lockpin::Verification result;
            try { result = lockpin::Verify(sid_, std::string_view(code.data(), length), record); }
            catch (...) { SecureZeroMemory(code.data(), code.size()); throw; }
            SecureZeroMemory(code.data(), code.size());

            if (result == lockpin::Verification::Accepted) {
                Log("TOTP accepted! Enrolled username: %ls", record.username);
                const auto hr = lockpin::PackIdentity(record.username, record.password, packed);
                if (SUCCEEDED(hr)) {
                    Log("PackIdentity SUCCEEDED!");
                    SetStatusText(L"验证完毕，随我进来吧");
                    *response = CPGSR_RETURN_CREDENTIAL_FINISHED;
                    *icon = CPSI_NONE;
                    return S_OK;
                }
                Log("PackIdentity failed: 0x%08X", hr);
            } else if (result == lockpin::Verification::Invalid) error = L"这个码不对呀...是不是过期了？换个新的看看";
            else if (result == lockpin::Verification::Cooldown) error = L"等等，试太快了！过60秒再来，着急就先用PIN";
            else error = L"时间对不上了...先用PIN进桌面，把电脑时间校准一下";
            SetStatusText(error);
            return SHStrDupW(error, message);
        }
        SecureZeroMemory(code.data(), code.size());

        // 3. User submitted without a complete TOTP code.
        //    IMPORTANT: We must NEVER block the LogonUI UI thread here.
        //    Background phone auto-unlock is fully event-driven via CredentialsChanged;
        //    if the phone confirms, LogonUI will call GetSerialization again automatically
        //    with ConsumeAutoUnlock() already satisfied (handled in step 1 above).
        if (length == 0) {
            const wchar_t* msg = phoneEnabled_ && authenticatorEnabled_
                ? L"按指纹还是输动态码？都行，你选。"
                : phoneEnabled_ ? L"手机上点“解锁”，按一下指纹，我就带你进桌面。" : L"把验证器里的6位动态码给我，我来核对。";
            Log("GetSerialization: Empty code submitted, returning immediately with prompt.");
            SetStatusText(msg);
            *response = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
            *icon = CPSI_WARNING;
            return SHStrDupW(msg, message);
        } else {
            // Partial input (1-5 digits) — must be exactly 6 digits.
            const wchar_t* msg = L"要完整的6位数字哦，再看看验证码。";
            Log("GetSerialization: Incomplete code (%zu digits) submitted, returning immediately.", length);
            SetStatusText(msg);
            *response = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
            *icon = CPSI_WARNING;
            return SHStrDupW(msg, message);
        }
    } catch (const std::exception& ex) {
        Log("Exception in GetSerialization: %s", ex.what());
        Clear();
    } catch (...) {
        Log("Unknown exception in GetSerialization");
        Clear();
    }
    return SHStrDupW(error, message);
}

class Factory final : public IClassFactory {
    long references_ = 1;
public:
    Factory() { InterlockedIncrement(&moduleReferences); }
    ~Factory() { InterlockedDecrement(&moduleReferences); }
    IFACEMETHODIMP QueryInterface(REFIID iid, void** result) override {
        if (!result) return E_POINTER;
        *result = nullptr;
        if (iid != IID_IUnknown && iid != IID_IClassFactory) return E_NOINTERFACE;
        *result = static_cast<IClassFactory*>(this); AddRef(); return S_OK;
    }
    IFACEMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&references_); }
    IFACEMETHODIMP_(ULONG) Release() override {
        const auto n = InterlockedDecrement(&references_); if (!n) delete this; return n;
    }
    IFACEMETHODIMP CreateInstance(IUnknown* outer, REFIID iid, void** result) override {
        *result = nullptr;
        if (outer) return CLASS_E_NOAGGREGATION;
        try {
            auto* provider = new Provider;
            const auto hr = provider->QueryInterface(iid, result);
            provider->Release();
            return hr;
        } catch (...) { return E_OUTOFMEMORY; }
    }
    IFACEMETHODIMP LockServer(BOOL lock) override {
        if (lock) InterlockedIncrement(&moduleReferences); else InterlockedDecrement(&moduleReferences);
        return S_OK;
    }
};
}

#ifdef LOCKPIN_TESTING
bool TestProviderRetryBeforeWait() {
    auto* provider = new Provider;
    const bool passed = provider->TestRetryBeforeWait();
    provider->Release();
    return passed;
}

bool TestProviderRetryWaitGeneration() {
    auto* provider = new Provider;
    const bool passed = provider->TestRetryWaitGeneration();
    provider->Release();
    return passed;
}
ICredentialProviderCredential2* MakeTestCredential() {
    return new Credential(nullptr, L"S-1-5-21-1-2-3-1001", L"Test User", true, true);
}
ICredentialProviderCredential2* MakeTestCredentialWithModes(bool authenticator, bool phone) {
    return new Credential(nullptr, L"S-1-5-21-1-2-3-1001", L"Test User", authenticator, phone);
}
#endif

extern "C" HRESULT __stdcall DllGetClassObject(REFCLSID clsid, REFIID iid, void** result) {
    if (!result) return E_POINTER;
    *result = nullptr;
    if (clsid != lockpin::ProviderId) return CLASS_E_CLASSNOTAVAILABLE;
    auto* factory = new (std::nothrow) Factory;
    if (!factory) return E_OUTOFMEMORY;
    const auto hr = factory->QueryInterface(iid, result); factory->Release(); return hr;
}
extern "C" HRESULT __stdcall DllCanUnloadNow() { return InterlockedCompareExchange(&moduleReferences, 0, 0) == 0 ? S_OK : S_FALSE; }
