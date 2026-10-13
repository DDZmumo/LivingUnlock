#include "auth.h"
#include <iostream>
#include <stdexcept>
#include <wincred.h>

extern "C" HRESULT __stdcall DllGetClassObject(REFCLSID, REFIID, void**);
extern "C" HRESULT __stdcall DllCanUnloadNow();
ICredentialProviderCredential2* MakeTestCredential();
bool TestProviderRetryBeforeWait();
bool TestProviderRetryWaitGeneration();
ICredentialProviderCredential2* MakeTestCredentialWithModes(bool, bool);
void Require(bool ok) { if (!ok) throw std::runtime_error("Provider test failed"); }
int main() {
    try {
        Require(SUCCEEDED(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED)));
        Require(TestProviderRetryBeforeWait());
        Require(TestProviderRetryWaitGeneration());
        // Load the actual deliverable, without registering it or starting LogonUI.
        auto dll = LoadLibraryW(L"build\\WindowsLockPin.dll");
        Require(dll != nullptr);
        Require(GetProcAddress(dll, "DllGetClassObject") && GetProcAddress(dll, "DllCanUnloadNow"));
        FreeLibrary(dll);
        Require(DllCanUnloadNow() == S_OK);
        IClassFactory* factory = nullptr;
        Require(SUCCEEDED(DllGetClassObject(lockpin::ProviderId, IID_PPV_ARGS(&factory))));
        Require(DllCanUnloadNow() == S_FALSE);
        ICredentialProvider* provider = nullptr;
        Require(SUCCEEDED(factory->CreateInstance(nullptr, IID_PPV_ARGS(&provider))));
        factory->Release();
        Require(provider->SetUsageScenario(CPUS_CREDUI, 0) == E_NOTIMPL);
        Require(provider->SetUsageScenario(CPUS_CHANGE_PASSWORD, 0) == E_NOTIMPL);
        const auto logon = provider->SetUsageScenario(CPUS_LOGON, 0);
        Require(logon == S_OK || (GetSystemMetrics(SM_REMOTESESSION) && logon == E_NOTIMPL));
        DWORD count = 0, selected = 0; BOOL automatic = TRUE;
        Require(SUCCEEDED(provider->GetCredentialCount(&count, &selected, &automatic)));
        Require(count == 0 && selected == CREDENTIAL_PROVIDER_NO_DEFAULT && !automatic);
        Require(SUCCEEDED(provider->GetFieldDescriptorCount(&count)) && count == 7);
        for (DWORD i = 0; i < count; ++i) {
            CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR* descriptor = nullptr;
            Require(SUCCEEDED(provider->GetFieldDescriptorAt(i, &descriptor)));
            Require(descriptor && descriptor->dwFieldID == i && descriptor->pszLabel);
            CoTaskMemFree(descriptor->pszLabel); CoTaskMemFree(descriptor);
        }
        provider->Release();
        auto* credential = MakeTestCredential();
        Require(SUCCEEDED(credential->SetSelected(&automatic)) && !automatic);
        // Field 4 is Code (PASSWORD_TEXT)
        Require(SUCCEEDED(credential->SetStringValue(4, L"123456")));
        PWSTR text = nullptr;
        Require(SUCCEEDED(credential->GetStringValue(4, &text)) && wcscmp(text, L"123456") == 0);
        CoTaskMemFree(text);
        Require(credential->SetStringValue(4, L"1234567") == E_INVALIDARG);
        Require(credential->SetStringValue(4, L"12345x") == E_INVALIDARG);
        Require(SUCCEEDED(credential->SetStringValue(4, L"123456")));
        Require(SUCCEEDED(credential->SetDeselected()));
        Require(SUCCEEDED(credential->GetStringValue(4, &text)) && !*text); CoTaskMemFree(text);
        // Field 2 is Status (SMALL_TEXT)
        Require(SUCCEEDED(credential->GetStringValue(2, &text)) && text != nullptr && wcslen(text) > 0);
        CoTaskMemFree(text);
        // Field 3 is Retry (COMMAND_LINK)
        Require(SUCCEEDED(credential->CommandLinkClicked(3)));
        Require(SUCCEEDED(credential->GetUserSid(&text)) && wcscmp(text, L"S-1-5-21-1-2-3-1001") == 0);
        CoTaskMemFree(text);
        HBITMAP bitmap = nullptr;
        Require(SUCCEEDED(credential->GetBitmapValue(0, &bitmap)) && bitmap);
#ifdef LOCKPIN_ICON_TEST
        BITMAP tile{};
        Require(GetObjectW(bitmap, sizeof(tile), &tile) == sizeof(tile));
        Require(tile.bmWidth == 96 && tile.bmHeight == 96);
#endif
        DeleteObject(bitmap);
        credential->Release();
        auto* phoneOnly = MakeTestCredentialWithModes(false, true);
        auto* authOnly = MakeTestCredentialWithModes(true, false);
        CREDENTIAL_PROVIDER_FIELD_STATE state{};
        CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE interactive{};
        Require(SUCCEEDED(phoneOnly->GetFieldState(4, &state, &interactive)) && state == CPFS_HIDDEN);
        Require(SUCCEEDED(phoneOnly->GetFieldState(5, &state, &interactive)) && state == CPFS_HIDDEN);
        Require(SUCCEEDED(phoneOnly->GetFieldState(2, &state, &interactive)) && state != CPFS_HIDDEN);
        Require(SUCCEEDED(authOnly->GetFieldState(2, &state, &interactive)) && state == CPFS_HIDDEN);
        Require(SUCCEEDED(authOnly->GetFieldState(3, &state, &interactive)) && state == CPFS_HIDDEN);
        Require(SUCCEEDED(authOnly->GetFieldState(4, &state, &interactive)) && state != CPFS_HIDDEN);
        phoneOnly->Release();
        authOnly->Release();
        Require(DllCanUnloadNow() == S_OK);
        // Synthetic data exercises identity-provider packing, not real authentication.
        wchar_t username[] = L"MicrosoftAccount\\test@example.invalid";
        wchar_t password[] = L"PUBLIC-NONCREDENTIAL-TEST-DATA";
        CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION packed{};
        Require(SUCCEEDED(lockpin::PackIdentity(username, password, &packed)));
        Require(packed.rgbSerialization && packed.cbSerialization && packed.clsidCredentialProvider == lockpin::ProviderId);
        SecureZeroMemory(packed.rgbSerialization, packed.cbSerialization); CoTaskMemFree(packed.rgbSerialization);
        CoUninitialize();
        std::cout << "PASS: DLL load, COM lifetime, fields, scenario isolation, OTP clearing, synthetic credential packing\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
