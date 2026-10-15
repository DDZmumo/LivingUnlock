#include "bluetooth_radio_watcher.h"
#include <bluetoothapis.h>

namespace lockpin::phone {
namespace {
// GUID_BTHPORT_DEVICE_INTERFACE from bthdef.h.
constexpr GUID RadioInterface = {0x0850302a, 0xb344, 0x4fda,
    {0x9b, 0xe9, 0x90, 0x57, 0x6b, 0x8d, 0x46, 0xf0}};
}

BluetoothRadioWatcher::~BluetoothRadioWatcher() {
    // Unregister waits for callbacks before their event/context is destroyed.
    if (notification_) CM_Unregister_Notification(notification_);
    if (changed_) CloseHandle(changed_);
}

bool BluetoothRadioWatcher::Start() noexcept {
    if (notification_) return true;
    if (!changed_) changed_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!changed_) return false;
    CM_NOTIFY_FILTER filter{};
    filter.cbSize = sizeof(filter);
    filter.FilterType = CM_NOTIFY_FILTER_TYPE_DEVICEINTERFACE;
    filter.u.DeviceInterface.ClassGuid = RadioInterface;
    return CM_Register_Notification(&filter, this, OnChange, &notification_) == CR_SUCCESS;
}

DWORD CALLBACK BluetoothRadioWatcher::OnChange(HCMNOTIFICATION, PVOID context,
    CM_NOTIFY_ACTION action, PCM_NOTIFY_EVENT_DATA, DWORD) noexcept {
    if (action == CM_NOTIFY_ACTION_DEVICEINTERFACEARRIVAL ||
        action == CM_NOTIFY_ACTION_DEVICEINTERFACEREMOVAL) {
        auto* self = static_cast<BluetoothRadioWatcher*>(context);
        SetEvent(self->changed_);
    }
    return ERROR_SUCCESS;
}

bool BluetoothRadioWatcher::Refresh() noexcept {
    // Reset before querying: a change concurrent with the query remains signaled.
    if (changed_) ResetEvent(changed_);
    // PnP may expose the interface before BluetoothGetRadioInfo is ready at
    // boot. Treat its presence as a signal to start the bounded setup window,
    // not as proof that the radio can already connect to a phone.
    ULONG interfaceListSize = 0;
    GUID interfaceClass = RadioInterface;
    if (CM_Get_Device_Interface_List_SizeW(&interfaceListSize, &interfaceClass,
        nullptr, CM_GET_DEVICE_INTERFACE_LIST_PRESENT) == CR_SUCCESS && interfaceListSize > 1) return true;
    BLUETOOTH_FIND_RADIO_PARAMS parameters{sizeof(parameters)};
    HANDLE radio = nullptr;
    const auto find = BluetoothFindFirstRadio(&parameters, &radio);
    if (!find) return false;
    BLUETOOTH_RADIO_INFO info{};
    info.dwSize = sizeof(info);
    const bool available = BluetoothGetRadioInfo(radio, &info) == ERROR_SUCCESS;
    CloseHandle(radio);
    BluetoothFindRadioClose(find);
    return available;
}
}
