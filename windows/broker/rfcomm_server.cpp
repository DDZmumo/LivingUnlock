#include "rfcomm_server.h"
#include "../common/broker_protocol.h"
#include <bluetoothapis.h>
#include <iomanip>
#include <sstream>
#include <vector>
#include <algorithm>

namespace lockpin::phone {
namespace {
bool ReceiveExact(SOCKET socket, std::uint8_t* output, std::size_t size) noexcept {
    std::size_t received = 0;
    while (received < size) {
        const auto count = recv(socket, reinterpret_cast<char*>(output + received), static_cast<int>(size - received), 0);
        if (count <= 0) return false;
        received += static_cast<std::size_t>(count);
    }
    return true;
}
}
RfcommServer::~RfcommServer() { Stop(); }
std::uint64_t PeerBluetoothAddress(SOCKET socket) noexcept {
    SOCKADDR_BTH peer{};
    int length = sizeof(peer);
    if (getpeername(socket, reinterpret_cast<sockaddr*>(&peer), &length) != 0 ||
        peer.addressFamily != AF_BTH) return 0;
    return peer.btAddr;
}

SOCKET ConnectPairedPhone(std::uint64_t address, std::uint32_t timeoutMs,
    const std::function<bool()>& cancelled) noexcept {
    if (!address || address > 0xFFFFFFFFFFFFULL || cancelled()) return INVALID_SOCKET;
    const SOCKET client = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
    if (client == INVALID_SOCKET) return INVALID_SOCKET;
    const auto fail = [&] { closesocket(client); return INVALID_SOCKET; };
    SOCKADDR_BTH target{};
    target.addressFamily = AF_BTH;
    target.btAddr = address;
    target.serviceClassId = PhoneListenerServiceId;
    u_long nonblocking = 1;
    if (ioctlsocket(client, FIONBIO, &nonblocking) != 0) return fail();
    const auto deadline = GetTickCount64() + timeoutMs;
    if (connect(client, reinterpret_cast<sockaddr*>(&target), sizeof(target)) == SOCKET_ERROR) {
        if (WSAGetLastError() != WSAEWOULDBLOCK) return fail();
        bool connected = false;
        while (!cancelled() && GetTickCount64() < deadline) {
            fd_set writeSet, errorSet;
            FD_ZERO(&writeSet); FD_ZERO(&errorSet);
            FD_SET(client, &writeSet); FD_SET(client, &errorSet);
            timeval wait{0, 100000};
            const int ready = select(0, nullptr, &writeSet, &errorSet, &wait);
            if (ready == SOCKET_ERROR || FD_ISSET(client, &errorSet)) return fail();
            if (ready > 0 && FD_ISSET(client, &writeSet)) {
                int error = 0, length = sizeof(error);
                if (getsockopt(client, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&error), &length) != 0 || error) return fail();
                connected = true;
                break;
            }
        }
        if (!connected) return fail();
    }
    if (cancelled()) return fail();
    nonblocking = 0;
    if (ioctlsocket(client, FIONBIO, &nonblocking) != 0) return fail();
    const DWORD ioTimeout = 20000;
    setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&ioTimeout), sizeof(ioTimeout));
    setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&ioTimeout), sizeof(ioTimeout));
    return client;
}
bool RfcommServer::Start(const std::wstring& serviceName) noexcept {
    Stop();
    WSADATA data{};
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) return false;
    winsockStarted_ = true;
    socket_ = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
    if (socket_ == INVALID_SOCKET) { Stop(); return false; }
    local_.addressFamily = AF_BTH;
    local_.port = BT_PORT_ANY;
    if (bind(socket_, reinterpret_cast<const sockaddr*>(&local_), sizeof(local_)) == SOCKET_ERROR) { Stop(); return false; }
    int length = sizeof(local_);
    if (getsockname(socket_, reinterpret_cast<sockaddr*>(&local_), &length) == SOCKET_ERROR) { Stop(); return false; }
    if (listen(socket_, 1) == SOCKET_ERROR) { Stop(); return false; }
    serviceName_ = serviceName;
    addressInfo_.LocalAddr.iSockaddrLength = sizeof(local_);
    addressInfo_.LocalAddr.lpSockaddr = reinterpret_cast<sockaddr*>(&local_);
    addressInfo_.RemoteAddr.iSockaddrLength = 0;
    addressInfo_.RemoteAddr.lpSockaddr = nullptr;
    addressInfo_.iSocketType = SOCK_STREAM;
    addressInfo_.iProtocol = BTHPROTO_RFCOMM;
    query_.dwSize = sizeof(query_);
    query_.lpszServiceInstanceName = const_cast<PWSTR>(serviceName_.c_str());
    query_.lpszComment = const_cast<PWSTR>(L"WindowsLockPin temporary pairing service");
    query_.dwNameSpace = NS_BTH;
    query_.lpServiceClassId = const_cast<GUID*>(&RfcommServiceId);
    query_.dwNumberOfCsAddrs = 1;
    query_.lpcsaBuffer = &addressInfo_;
    if (WSASetServiceW(&query_, RNRSERVICE_REGISTER, 0) == SOCKET_ERROR) { Stop(); return false; }
    advertised_ = true;
    return true;
}
SOCKET RfcommServer::Accept(std::uint32_t timeoutMs) noexcept {
    if (socket_ == INVALID_SOCKET) return INVALID_SOCKET;
    fd_set readSet; FD_ZERO(&readSet); FD_SET(socket_, &readSet);
    timeval timeout{static_cast<long>(timeoutMs / 1000), static_cast<long>((timeoutMs % 1000) * 1000)};
    if (select(0, &readSet, nullptr, nullptr, &timeout) <= 0) return INVALID_SOCKET;
    SOCKET client = accept(socket_, nullptr, nullptr);
    if (client != INVALID_SOCKET) {
        const DWORD ioTimeout = 20000;
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&ioTimeout), sizeof(ioTimeout));
        setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&ioTimeout), sizeof(ioTimeout));
    }
    return client;
}
std::string RfcommServer::LocalBluetoothAddress() const {
    BLUETOOTH_FIND_RADIO_PARAMS parameters{sizeof(parameters)};
    HANDLE radio = nullptr;
    HBLUETOOTH_RADIO_FIND find = BluetoothFindFirstRadio(&parameters, &radio);
    if (!find) return {};
    BLUETOOTH_RADIO_INFO info{sizeof(info)};
    const bool ok = BluetoothGetRadioInfo(radio, &info) == ERROR_SUCCESS;
    CloseHandle(radio); BluetoothFindRadioClose(find);
    if (!ok) return {};
    const auto address = info.address.ullLong;
    std::ostringstream output; output << std::uppercase << std::hex << std::setfill('0');
    for (int shift = 40; shift >= 0; shift -= 8) {
        if (shift != 40) output << ':';
        output << std::setw(2) << ((address >> shift) & 0xff);
    }
    return output.str();
}
void RfcommServer::Stop() noexcept {
    if (advertised_) { WSASetServiceW(&query_, RNRSERVICE_DELETE, 0); advertised_ = false; }
    if (socket_ != INVALID_SOCKET) { closesocket(socket_); socket_ = INVALID_SOCKET; }
    if (winsockStarted_) { WSACleanup(); winsockStarted_ = false; }
    local_ = {}; addressInfo_ = {}; query_ = {}; serviceName_.clear();
}
bool ReceiveFrame(SOCKET socket, std::vector<std::uint8_t>& frame) noexcept {
    frame.assign(FrameHeaderSize, 0);
    if (!ReceiveExact(socket, frame.data(), frame.size())) return false;
    const std::size_t payload = (static_cast<std::size_t>(frame[14]) << 8) | frame[15];
    if (payload > MaxPayloadSize) return false;
    frame.resize(FrameHeaderSize + payload);
    return payload == 0 || ReceiveExact(socket, frame.data() + FrameHeaderSize, payload);
}
bool SendAll(SOCKET socket, const std::vector<std::uint8_t>& bytes) noexcept {
    std::size_t sent = 0;
    while (sent < bytes.size()) {
        const auto count = send(socket, reinterpret_cast<const char*>(bytes.data() + sent), static_cast<int>(bytes.size() - sent), 0);
        if (count <= 0) return false;
        sent += static_cast<std::size_t>(count);
    }
    return true;
}
bool ReceiveFrameUntil(SOCKET socket, std::vector<std::uint8_t>& frame, std::uint64_t deadlineTickMs) noexcept {
    const auto receive = [&](std::uint8_t* output, std::size_t size) {
        std::size_t received=0;
        while(received<size) {
            const auto now=GetTickCount64();
            if(now>=deadlineTickMs)return false;
            const DWORD timeout=static_cast<DWORD>((std::min)(deadlineTickMs-now,static_cast<std::uint64_t>(60000)));
            if(setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<const char*>(&timeout),sizeof(timeout))==SOCKET_ERROR)return false;
            const auto n=recv(socket,reinterpret_cast<char*>(output+received),static_cast<int>(size-received),0);
            if(n<=0)return false;
            received+=static_cast<std::size_t>(n);
        }
        return GetTickCount64()<deadlineTickMs;
    };
    frame.assign(FrameHeaderSize,0);
    if(!receive(frame.data(),frame.size()))return false;
    const auto payload=(static_cast<std::size_t>(frame[14])<<8)|frame[15];
    if(payload>MaxPayloadSize)return false;
    frame.resize(FrameHeaderSize+payload);
    return payload==0 || receive(frame.data()+FrameHeaderSize,payload);
}
}
