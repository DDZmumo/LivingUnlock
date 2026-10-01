#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <sddl.h>
#include <aclapi.h>
#include <shlobj.h>
#include <wincrypt.h>
#include "phone_vault.h"
#include <stdexcept>
#include <vector>
#include <cwchar>
#include <cstddef>

namespace lockpin::phone {
namespace {
constexpr wchar_t SecurityDescriptorSddl[] = L"O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)";

struct AutoHandle {
    HANDLE handle = INVALID_HANDLE_VALUE;
    explicit AutoHandle(HANDLE h = INVALID_HANDLE_VALUE) : handle(h) {}
    ~AutoHandle() { if (handle && handle != INVALID_HANDLE_VALUE) CloseHandle(handle); }
    AutoHandle(const AutoHandle&) = delete;
    AutoHandle& operator=(const AutoHandle&) = delete;
};

template<class T>
struct LocalMem {
    T* ptr = nullptr;
    ~LocalMem() { if (ptr) LocalFree(ptr); }
};

void ThrowIfFalse(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

std::wstring GetCurrentProcessSid() {
    AutoHandle token;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token.handle)) return {};
    DWORD size = 0;
    GetTokenInformation(token.handle, TokenUser, nullptr, 0, &size);
    if (size == 0) return {};
    std::vector<BYTE> buffer(size);
    if (!GetTokenInformation(token.handle, TokenUser, buffer.data(), size, &size)) return {};
    LocalMem<wchar_t> strSid;
    if (!ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(buffer.data())->User.Sid, &strSid.ptr)) return {};
    return strSid.ptr ? std::wstring(strSid.ptr) : std::wstring();
}

struct SecurityDescriptorWrapper {
    PSECURITY_DESCRIPTOR value = nullptr;
    SecurityDescriptorWrapper() {
        const auto sid = GetCurrentProcessSid();
        std::wstring sddl = L"D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)";
        if (!sid.empty()) {
            sddl += L"(A;OICI;FA;;;" + sid + L")";
        }
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.c_str(), SDDL_REVISION_1, &value, nullptr);
    }
    ~SecurityDescriptorWrapper() { if (value) LocalFree(value); }
};

bool ValidateSidString(const std::wstring& sid) noexcept {
    if (sid.empty() || sid.size() >= 184) return false;
    LocalMem<void> parsed;
    if (!ConvertStringSidToSidW(sid.c_str(), &parsed.ptr)) return false;
    LocalMem<wchar_t> canonical;
    if (!ConvertSidToStringSidW(parsed.ptr, &canonical.ptr)) return false;
    return canonical.ptr && sid == canonical.ptr;
}

std::wstring RecordFilePath(const std::wstring& directory, const std::wstring& sid) {
    return directory + L"\\phone_" + sid + L".dat";
}
}

PairedDeviceRecord::~PairedDeviceRecord() {
    SecureZeroMemory(this, sizeof(*this));
}

std::wstring DefaultPhoneVaultDirectory() {
    wchar_t envDir[MAX_PATH]{};
    DWORD len = GetEnvironmentVariableW(L"LOCKPIN_PHONE_VAULT_DIR", envDir, MAX_PATH);
    if (len > 0 && len < MAX_PATH) {
        return std::wstring(envDir);
    }
    PWSTR path = nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_ProgramData, 0, nullptr, &path)) && path) {
        std::wstring result = std::wstring(path) + L"\\WindowsLockPin";
        CoTaskMemFree(path);
        // Test if ProgramData directory can be accessed or created
        if (CreateDirectoryW(result.c_str(), nullptr) || GetLastError() == ERROR_ALREADY_EXISTS) {
            AutoHandle testHandle(CreateFileW(result.c_str(), GENERIC_READ,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr));
            if (testHandle.handle != INVALID_HANDLE_VALUE) {
                return result;
            }
        }
        // If ProgramData\WindowsLockPin is access-denied to current user token, fallback to LocalAppData
        PWSTR localApp = nullptr;
        if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &localApp)) && localApp) {
            std::wstring fallback = std::wstring(localApp) + L"\\WindowsLockPin";
            CoTaskMemFree(localApp);
            CreateDirectoryW(fallback.c_str(), nullptr);
            return fallback;
        }
        return result;
    }
    return L"C:\\ProgramData\\WindowsLockPin";
}

void SavePairedPhone(const PairedDeviceRecord& record, const std::wstring& vaultDir) {
    static_assert(offsetof(PairedDeviceRecord, phoneBluetoothAddress) == 696);
    static_assert(sizeof(PairedDeviceRecord) == 704);
    ThrowIfFalse(record.version == 1 || record.version == 2, "Unsupported phone record version");
    ThrowIfFalse(record.phoneBluetoothAddress <= 0xFFFFFFFFFFFFULL &&
        (record.version == 2 || record.phoneBluetoothAddress == 0), "Invalid phone route");
    const std::wstring sid(record.sid);
    ThrowIfFalse(ValidateSidString(sid), "Invalid SID for paired phone");
    const std::wstring dir = vaultDir.empty() ? DefaultPhoneVaultDirectory() : vaultDir;
    CreateDirectoryW(dir.c_str(), nullptr);

    DATA_BLOB input{};
    input.cbData = record.version == 1 ? 696 : sizeof(PairedDeviceRecord);
    input.pbData = reinterpret_cast<BYTE*>(const_cast<PairedDeviceRecord*>(&record));

    DATA_BLOB encrypted{};
    ThrowIfFalse(CryptProtectData(&input, L"WindowsLockPin Phone v1", nullptr, nullptr, nullptr,
        CRYPTPROTECT_LOCAL_MACHINE | CRYPTPROTECT_UI_FORBIDDEN, &encrypted),
        "DPAPI encryption failed for paired phone record");

    struct EncryptedCleanup {
        DATA_BLOB& blob;
        ~EncryptedCleanup() { if (blob.pbData) { SecureZeroMemory(blob.pbData, blob.cbData); LocalFree(blob.pbData); } }
    } cleanup{encrypted};

    SecurityDescriptorWrapper sec;
    SECURITY_ATTRIBUTES attrs{sizeof(attrs), sec.value, FALSE};

    const std::wstring targetPath = RecordFilePath(dir, sid);
    const std::wstring tempPath = targetPath + L".pending";
    DeleteFileW(tempPath.c_str());

    {
        AutoHandle file(CreateFileW(tempPath.c_str(), GENERIC_WRITE | READ_CONTROL, 0,
            sec.value ? &attrs : nullptr, CREATE_NEW,
            FILE_ATTRIBUTE_NORMAL | FILE_FLAG_WRITE_THROUGH, nullptr));
        ThrowIfFalse(file.handle != INVALID_HANDLE_VALUE, "Unable to create pending paired phone file");

        DWORD written = 0;
        ThrowIfFalse(WriteFile(file.handle, encrypted.pbData, encrypted.cbData, &written, nullptr) &&
            written == encrypted.cbData, "Unable to write paired phone data");
        FlushFileBuffers(file.handle);
    }

    ThrowIfFalse(MoveFileExW(tempPath.c_str(), targetPath.c_str(),
        MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH),
        "Unable to commit paired phone record file");
}

std::optional<PairedDeviceRecord> LoadPairedPhone(const std::wstring& sid, const std::wstring& vaultDir) {
    if (!ValidateSidString(sid)) return std::nullopt;
    const std::wstring dir = vaultDir.empty() ? DefaultPhoneVaultDirectory() : vaultDir;
    if (GetFileAttributesW((dir + L"\\phone_" + sid + L".disabled").c_str()) != INVALID_FILE_ATTRIBUTES)
        return std::nullopt;
    const std::wstring path = RecordFilePath(dir, sid);

    AutoHandle file(CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
        OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, nullptr));
    if (file.handle == INVALID_HANDLE_VALUE && vaultDir.empty()) {
        std::wstring regSubKey = L"SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\ProfileList\\" + sid;
        HKEY hKey = nullptr;
        if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, regSubKey.c_str(), 0, KEY_READ, &hKey) == ERROR_SUCCESS) {
            wchar_t profileDir[MAX_PATH]{};
            DWORD dataSize = sizeof(profileDir);
            DWORD type = 0;
            if (RegQueryValueExW(hKey, L"ProfileImagePath", nullptr, &type,
                reinterpret_cast<LPBYTE>(profileDir), &dataSize) == ERROR_SUCCESS) {
                wchar_t expandedDir[MAX_PATH]{};
                if (ExpandEnvironmentStringsW(profileDir, expandedDir, MAX_PATH) > 0) {
                    std::wstring fallbackPath = std::wstring(expandedDir) + L"\\AppData\\Local\\WindowsLockPin\\phone_" + sid + L".dat";
                    file.handle = CreateFileW(fallbackPath.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                        OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
                }
            }
            RegCloseKey(hKey);
        }
    }
    if (file.handle == INVALID_HANDLE_VALUE) return std::nullopt;

    BY_HANDLE_FILE_INFORMATION info{};
    if (!GetFileInformationByHandle(file.handle, &info) ||
        (info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
        return std::nullopt;
    }

    LARGE_INTEGER fileSize{};
    if (!GetFileSizeEx(file.handle, &fileSize) || fileSize.QuadPart <= 0 || fileSize.QuadPart > 65536) {
        return std::nullopt;
    }

    std::vector<BYTE> ciphertext(static_cast<std::size_t>(fileSize.QuadPart));
    DWORD read = 0;
    if (!ReadFile(file.handle, ciphertext.data(), static_cast<DWORD>(ciphertext.size()), &read, nullptr) ||
        read != ciphertext.size()) {
        return std::nullopt;
    }

    DATA_BLOB cipherBlob{};
    cipherBlob.cbData = static_cast<DWORD>(ciphertext.size());
    cipherBlob.pbData = ciphertext.data();

    DATA_BLOB plainBlob{};
    if (!CryptUnprotectData(&cipherBlob, nullptr, nullptr, nullptr, nullptr,
        CRYPTPROTECT_UI_FORBIDDEN, &plainBlob) || !plainBlob.pbData) {
        return std::nullopt;
    }

    struct PlainCleanup {
        DATA_BLOB& blob;
        ~PlainCleanup() { if (blob.pbData) { SecureZeroMemory(blob.pbData, blob.cbData); LocalFree(blob.pbData); } }
    } plainCleanup{plainBlob};

    if (plainBlob.cbData != 696 && plainBlob.cbData != sizeof(PairedDeviceRecord)) return std::nullopt;

    PairedDeviceRecord record;
    memcpy(&record, plainBlob.pbData, plainBlob.cbData);
    if (record.magic != 0x50484c50 ||
        !((record.version == 1 && plainBlob.cbData == 696) ||
          (record.version == 2 && plainBlob.cbData == sizeof(PairedDeviceRecord))) ||
        record.phoneBluetoothAddress > 0xFFFFFFFFFFFFULL ||
        record.sid[183] != 0 || sid != record.sid || record.pcId[64] != 0 ||
        record.deviceId[64] != 0 || record.deviceName[64] != 0 || record.bluetoothMac[17] != 0) {
        SecureZeroMemory(&record, sizeof(record));
        return std::nullopt;
    }

    if (vaultDir.empty()) {
        try {
            SavePairedPhone(record, dir);
        } catch (...) {}
    }

    return record;
}

bool IsPhonePaired(const std::wstring& sid, const std::wstring& vaultDir) {
    return LoadPairedPhone(sid, vaultDir).has_value();
}

bool RemovePairedPhone(const std::wstring& sid, const std::wstring& vaultDir) {
    if (!ValidateSidString(sid)) return false;
    const std::wstring dir = vaultDir.empty() ? DefaultPhoneVaultDirectory() : vaultDir;
    const std::wstring path = RecordFilePath(dir, sid);
    bool deleted = (DeleteFileW(path.c_str()) != FALSE);

    if (vaultDir.empty()) {
        std::wstring regSubKey = L"SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\ProfileList\\" + sid;
        HKEY hKey = nullptr;
        if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, regSubKey.c_str(), 0, KEY_READ, &hKey) == ERROR_SUCCESS) {
            wchar_t profileDir[MAX_PATH]{};
            DWORD dataSize = sizeof(profileDir);
            DWORD type = 0;
            if (RegQueryValueExW(hKey, L"ProfileImagePath", nullptr, &type,
                reinterpret_cast<LPBYTE>(profileDir), &dataSize) == ERROR_SUCCESS) {
                wchar_t expandedDir[MAX_PATH]{};
                if (ExpandEnvironmentStringsW(profileDir, expandedDir, MAX_PATH) > 0) {
                    std::wstring fallbackPath = std::wstring(expandedDir) + L"\\AppData\\Local\\WindowsLockPin\\phone_" + sid + L".dat";
                    if (DeleteFileW(fallbackPath.c_str())) deleted = true;
                }
            }
            RegCloseKey(hKey);
        }
    }
    return deleted;
}

} // namespace lockpin::phone
