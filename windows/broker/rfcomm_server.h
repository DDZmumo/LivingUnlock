#pragma once
#include <winsock2.h>
#include <ws2bth.h>
#include <cstdint>
#include <string>
#include <vector>
#include <functional>

namespace lockpin::phone {
inline constexpr GUID RfcommServiceId = {0x9b3f4a10, 0x7c22, 0x4e89, {0x80,0xb1,0x5d,0x9c,0x71,0xa3,0xd0,0xf2}};
inline constexpr GUID PhoneListenerServiceId = {0x9b3f4a10, 0x7c22, 0x4e89, {0x80,0xb1,0x5d,0x9c,0x71,0xa3,0xd0,0xf3}};
std::uint64_t PeerBluetoothAddress(SOCKET socket) noexcept;
SOCKET ConnectPairedPhone(std::uint64_t address, std::uint32_t timeoutMs,
    const std::function<bool()>& cancelled) noexcept;

class RfcommServer {
public:
    RfcommServer() = default;
    ~RfcommServer();
    RfcommServer(const RfcommServer&) = delete;
    RfcommServer& operator=(const RfcommServer&) = delete;
    bool Start(const std::wstring& serviceName) noexcept;
    SOCKET Accept(std::uint32_t timeoutMs) noexcept;
    std::string LocalBluetoothAddress() const;
    void Stop() noexcept;
private:
    bool winsockStarted_ = false;
    bool advertised_ = false;
    SOCKET socket_ = INVALID_SOCKET;
    SOCKADDR_BTH local_{};
    CSADDR_INFO addressInfo_{};
    WSAQUERYSETW query_{};
    std::wstring serviceName_;
};

bool ReceiveFrame(SOCKET socket, std::vector<std::uint8_t>& frame) noexcept;
bool ReceiveFrameUntil(SOCKET socket, std::vector<std::uint8_t>& frame, std::uint64_t deadlineTickMs) noexcept;
bool SendAll(SOCKET socket, const std::vector<std::uint8_t>& bytes) noexcept;
}
