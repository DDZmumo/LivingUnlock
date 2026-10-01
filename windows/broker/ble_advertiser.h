#pragma once
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <bluetoothleapis.h>
#include <string>
#include <cstdint>

namespace lockpin::phone {

// Advertises a "PC is locked" BLE beacon.
// Uses WinRT manufacturer data (company 0xFFFF, LULK v1) for filtered Android scanning.
class BleAdvertiser {
public:
    BleAdvertiser() = default;
    ~BleAdvertiser() { Stop(); }
    BleAdvertiser(const BleAdvertiser&) = delete;
    BleAdvertiser& operator=(const BleAdvertiser&) = delete;

    // Includes the RFCOMM address and a fresh nonce for one wake per explicit request.
    bool Start(const std::string& bluetoothAddress) noexcept;
    void Stop() noexcept;
    bool IsRunning() const noexcept { return handle_ != nullptr; }

private:
    HANDLE handle_ = nullptr;
};

} // namespace lockpin::phone
