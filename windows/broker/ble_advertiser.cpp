// ble_advertiser.cpp — Isolated WinRT compilation unit for BLE advertising.
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Devices.Bluetooth.Advertisement.h>
#include <winrt/Windows.Storage.Streams.h>

#include "ble_advertiser.h"
#include <cstring>
#include <memory>
#include <array>
#include <bcrypt.h>

namespace lockpin::phone {

namespace {
using namespace winrt::Windows::Devices::Bluetooth::Advertisement;
using namespace winrt::Windows::Storage::Streams;

// Store the publisher as a raw allocation using winrt's ref-counting via a heap-allocated wrapper
struct PublisherHolder {
    BluetoothLEAdvertisementPublisher publisher;
};

} // namespace

bool BleAdvertiser::Start(const std::string& bluetoothAddress) noexcept {
    try {
        Stop();

        winrt::init_apartment(winrt::apartment_type::multi_threaded);

        auto holder = std::make_unique<PublisherHolder>();
        auto& pub = holder->publisher;

        // Publisher reserves service-UUID AD types. Use a manufacturer payload:
        // "LULK", version 1, classic Bluetooth MAC (6 bytes), beacon nonce (8 bytes).
        auto adv = pub.Advertisement();
        if (bluetoothAddress.size() != 17) return false;
        std::array<unsigned char, 6> address{};
        const auto hex = [](char ch) -> int {
            if (ch >= '0' && ch <= '9') return ch - '0';
            if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
            if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
            return -1;
        };
        for (std::size_t i = 0; i < address.size(); ++i) {
            const int hi = hex(bluetoothAddress[i * 3]);
            const int lo = hex(bluetoothAddress[i * 3 + 1]);
            if (hi < 0 || lo < 0 || (i < 5 && bluetoothAddress[i * 3 + 2] != ':')) return false;
            address[i] = static_cast<unsigned char>((hi << 4) | lo);
        }
        std::array<unsigned char, 8> nonce{};
        if (BCryptGenRandom(nullptr, nonce.data(), static_cast<ULONG>(nonce.size()), BCRYPT_USE_SYSTEM_PREFERRED_RNG) < 0) return false;
        BluetoothLEManufacturerData mfr;
        mfr.CompanyId(0xFFFF);
        DataWriter writer;
        for (const auto byte : std::array<unsigned char, 5>{'L','U','L','K',1}) writer.WriteByte(byte);
        for (const auto byte : address) writer.WriteByte(byte);
        for (const auto byte : nonce) writer.WriteByte(byte);
        mfr.Data(writer.DetachBuffer());
        adv.ManufacturerData().Append(mfr);

        pub.Start();

        for (int attempt = 0; attempt < 40; ++attempt) {
            const auto status = pub.Status();
            if (status == BluetoothLEAdvertisementPublisherStatus::Started) {
                handle_ = holder.release();
                return true;
            }
            if (status == BluetoothLEAdvertisementPublisherStatus::Aborted) break;
            Sleep(50);
        }
        pub.Stop();
        return false;
    } catch (...) {
        handle_ = nullptr;
        return false;
    }
}

void BleAdvertiser::Stop() noexcept {
    if (handle_) {
        try {
            auto* h = static_cast<PublisherHolder*>(handle_);
            h->publisher.Stop();
            delete h;
        } catch (...) {}
        handle_ = nullptr;
    }
}

} // namespace lockpin::phone
