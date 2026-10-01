#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include <optional>

namespace lockpin::phone {

struct PairedDeviceRecord {
    std::uint32_t magic = 0x50484c50; // 'PLHP' (Phone LockPin)
    std::uint32_t version = 2;
    wchar_t sid[184]{};
    char pcId[65]{};
    char deviceId[65]{};
    char deviceName[65]{};
    char bluetoothMac[18]{};
    std::uint8_t clientPublicKey[65]{};
    std::uint8_t kPair[32]{};
    std::uint64_t pairedTimestampSec = 0;
    // v2 extension: authenticated RFCOMM peer address, never inferred from its name.
    std::uint64_t phoneBluetoothAddress = 0;

    PairedDeviceRecord() = default;
    ~PairedDeviceRecord();
};

std::wstring DefaultPhoneVaultDirectory();
void SavePairedPhone(const PairedDeviceRecord& record, const std::wstring& vaultDir = L"");
std::optional<PairedDeviceRecord> LoadPairedPhone(const std::wstring& sid, const std::wstring& vaultDir = L"");
bool IsPhonePaired(const std::wstring& sid, const std::wstring& vaultDir = L"");
bool RemovePairedPhone(const std::wstring& sid, const std::wstring& vaultDir = L"");

} // namespace lockpin::phone
