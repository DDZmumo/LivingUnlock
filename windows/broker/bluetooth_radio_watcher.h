#pragma once
#include <winsock2.h>
#include <windows.h>
#include <cfgmgr32.h>

namespace lockpin::phone {
// Register before taking the initial radio snapshot, so arrivals between the
// snapshot and the wait cannot be lost. The callback only signals an event.
class BluetoothRadioWatcher {
public:
    BluetoothRadioWatcher() = default;
    ~BluetoothRadioWatcher();
    BluetoothRadioWatcher(const BluetoothRadioWatcher&) = delete;
    BluetoothRadioWatcher& operator=(const BluetoothRadioWatcher&) = delete;
    bool Start() noexcept;
    HANDLE ChangeEvent() const noexcept { return notification_ ? changed_ : nullptr; }
    bool Refresh() noexcept;

private:
    static DWORD CALLBACK OnChange(HCMNOTIFICATION, PVOID context,
        CM_NOTIFY_ACTION action, PCM_NOTIFY_EVENT_DATA, DWORD) noexcept;
    HCMNOTIFICATION notification_ = nullptr;
    HANDLE changed_ = nullptr;
};
}
