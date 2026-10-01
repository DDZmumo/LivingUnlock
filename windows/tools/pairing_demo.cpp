#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <sddl.h>
#include "../broker/rfcomm_server.h"
#include "../broker/session_store.h"
#include "../common/broker_protocol.h"
#include "../common/pairing_crypto.h"
#include "../common/phone_messages.h"
#include "../common/phone_vault.h"
#include "../common/device_info.h"
#include "qrcodegen.hpp"
#include <algorithm>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>

namespace {
std::string Hex(const std::uint8_t* bytes, std::size_t size) {
    std::ostringstream output; output << std::hex << std::setfill('0');
    for (std::size_t i = 0; i < size; ++i) output << std::setw(2) << static_cast<unsigned>(bytes[i]);
    return output.str();
}
std::string Base64Url(const std::vector<std::uint8_t>& bytes) {
    constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string output;
    unsigned accumulator = 0, bits = 0;
    for (const auto value : bytes) {
        accumulator = (accumulator << 8) | value; bits += 8;
        while (bits >= 6) { bits -= 6; output += alphabet[(accumulator >> bits) & 63]; }
    }
    if (bits) output += alphabet[(accumulator << (6 - bits)) & 63];
    return output;
}
std::string ComputerName() {
    wchar_t name[256]{}; DWORD size = static_cast<DWORD>(std::size(name));
    if (!GetComputerNameW(name, &size)) return "Windows-PC";
    std::string result;
    for (DWORD i = 0; i < size && result.size() < 64; ++i) {
        const auto value = name[i];
        result += (value < 128 && (isalnum(static_cast<unsigned char>(value)) || value == ' ' || value == '_' || value == '.' || value == '-'))
            ? static_cast<char>(value) : '-';
    }
    return result.empty() ? "Windows-PC" : result;
}
void WriteQrSvg(const std::filesystem::path& path, const std::string& text) {
    const auto qr = qrcodegen::QrCode::encodeText(text.c_str(), qrcodegen::QrCode::Ecc::MEDIUM);
    constexpr int border = 4;
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) throw std::runtime_error("Unable to create QR SVG");
    output << "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " << qr.getSize() + border * 2 << ' '
           << qr.getSize() + border * 2 << "\" shape-rendering=\"crispEdges\">"
           << "<rect width=\"100%\" height=\"100%\" fill=\"white\"/><path fill=\"black\" d=\"";
    for (int y = 0; y < qr.getSize(); ++y) for (int x = 0; x < qr.getSize(); ++x)
        if (qr.getModule(x, y)) output << "M" << x + border << ',' << y + border << "h1v1h-1z";
    output << "\"/></svg>";
    if (!output) throw std::runtime_error("Unable to write QR SVG");
}
bool TokenMatches(const std::vector<std::uint8_t>& left, const std::vector<std::uint8_t>& right) {
    if (left.size() != right.size()) return false;
    std::uint8_t difference = 0;
    for (std::size_t i = 0; i < left.size(); ++i) difference |= left[i] ^ right[i];
    return difference == 0;
}
struct TemporaryQr {
    std::filesystem::path path;
    ~TemporaryQr() { if (!path.empty()) DeleteFileW(path.c_str()); }
};
}

int wmain(int argc, wchar_t** argv) {
    if (argc != 2) { std::cerr << "Usage: phone_pairing_demo.exe <temporary-qr.svg>\n"; return 2; }
    TemporaryQr qrFile{std::filesystem::absolute(argv[1])};
    try {
        lockpin::phone::RfcommServer server;
        if (!server.Start(L"WindowsLockPin Pairing")) throw std::runtime_error("Unable to start Bluetooth RFCOMM server");
        const auto bluetoothAddress = server.LocalBluetoothAddress();
        if (bluetoothAddress.empty()) throw std::runtime_error("Unable to read local Bluetooth address");

        lockpin::phone::BcryptRandomSource random;
        std::array<std::uint8_t, 16> pcIdBytes{};
        std::vector<std::uint8_t> token(32);
        if (!random.Fill(pcIdBytes.data(), pcIdBytes.size()) || !random.Fill(token.data(), token.size()))
            throw std::runtime_error("Unable to generate pairing values");
        const auto pcId = Hex(pcIdBytes.data(), pcIdBytes.size());
        const auto expires = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count() + 180;
        std::string uri = "wslp://pair?v=1&pc_id=" + pcId + "&pc_name=" + ComputerName() +
            "&bt_mac=" + bluetoothAddress + "&pair_token=" + Base64Url(token) + "&exp=" + std::to_string(expires);
        WriteQrSvg(qrFile.path, uri);
        std::cout << "PAIRING_READY\nQR_PATH=" << qrFile.path.u8string() << "\nPAIRING_URI=" << uri << "\nEXPIRES_UNIX=" << expires << std::endl;

        const SOCKET client = server.Accept(180000);
        if (client == INVALID_SOCKET) throw std::runtime_error("Pairing connection timed out");
        struct SocketCloser { SOCKET value; ~SocketCloser() { if (value != INVALID_SOCKET) closesocket(value); } } closer{client};
        std::vector<std::uint8_t> wire;
        if (!lockpin::phone::ReceiveFrame(client, wire)) {
            // An old background listener can disconnect while the user unpairs
            // on the phone. No pairing request was accepted or saved in this case.
            SecureZeroMemory(token.data(), token.size());
            SecureZeroMemory(uri.data(), uri.size());
            std::cout << "PAIRING_CANCELLED=Connection ended before a pairing request was received" << std::endl;
            return 0;
        }
        const auto frame = lockpin::phone::DecodeFrame(wire.data(), wire.size());
        if (!frame.frame || frame.frame->type != lockpin::phone::MessageType::PairRequest)
            throw std::runtime_error("Unexpected pairing frame");
        auto request = lockpin::phone::DecodePairRequest(frame.frame->payload.data(), frame.frame->payload.size());
        if (!request || !TokenMatches(token, request->pairingToken) ||
            !lockpin::phone::ValidateP256PublicKey(request->clientPublicKey))
            throw std::runtime_error("Pairing request authentication failed");

        const auto serverKey = lockpin::phone::GenerateP256PublicKey();
        const auto pairingKey = lockpin::phone::DerivePairingKey(pcId, request->deviceId, token);
        const auto transcript = lockpin::phone::BuildPairConfirmationTranscript(pcId, request->deviceId,
            request->clientPublicKey, serverKey);
        const auto tag = lockpin::phone::ComputePairConfirmationTag(pairingKey, transcript);
        lockpin::phone::PairResponsePayload response{0, serverKey,
            std::vector<std::uint8_t>(tag.begin(), tag.end())};
        lockpin::phone::Frame responseFrame{lockpin::phone::MessageType::PairResponse,
            frame.frame->requestId, lockpin::phone::EncodePairResponse(response)};
        if (!lockpin::phone::SendAll(client, lockpin::phone::EncodeFrame(responseFrame)))
            throw std::runtime_error("Unable to send pairing response");

        // Persist to Windows Phone Vault
        HANDLE procToken = nullptr;
        if (OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &procToken)) {
            DWORD size = 0;
            GetTokenInformation(procToken, TokenUser, nullptr, 0, &size);
            std::vector<BYTE> buffer(size);
            if (size > 0 && GetTokenInformation(procToken, TokenUser, buffer.data(), size, &size)) {
                LPWSTR strSid = nullptr;
                if (ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(buffer.data())->User.Sid, &strSid)) {
                    lockpin::phone::PairedDeviceRecord record;
                    wcsncpy_s(record.sid, strSid, _TRUNCATE);
                    strncpy_s(record.pcId, pcId.c_str(), _TRUNCATE);
                    strncpy_s(record.deviceId, request->deviceId.c_str(), _TRUNCATE);
                    strncpy_s(record.deviceName, request->deviceName.c_str(), _TRUNCATE);
                    strncpy_s(record.bluetoothMac, bluetoothAddress.c_str(), _TRUNCATE);
                    record.phoneBluetoothAddress = lockpin::phone::PeerBluetoothAddress(client);
                    if (request->clientPublicKey.size() == 65) {
                        memcpy(record.clientPublicKey, request->clientPublicKey.data(), 65);
                    }
                    memcpy(record.kPair, pairingKey.data(), 32);
                    record.pairedTimestampSec = static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::seconds>(
                        std::chrono::system_clock::now().time_since_epoch()).count());

                    lockpin::phone::SavePairedPhone(record);
                    try {
                        auto info=lockpin::phone::EncryptDeviceInfo(lockpin::phone::BuildDeviceInfo(pcId,strSid),
                            std::vector<std::uint8_t>(pairingKey.begin(),pairingKey.end()));
                        lockpin::phone::SendAll(client,lockpin::phone::EncodeFrame({lockpin::phone::MessageType::Pong,frame.frame->requestId,std::move(info)}));
                    } catch (...) { /* Device details never change pairing outcome. */ }
                    std::wcout << L"VAULT_SAVED_SID=" << strSid << std::endl;
                    LocalFree(strSid);
                }
            }
            CloseHandle(procToken);
        }

        SecureZeroMemory(token.data(), token.size());
        SecureZeroMemory(uri.data(), uri.size());
        std::cout << "PAIRING_SUCCESS\nDEVICE_NAME=" << request->deviceName << std::endl;
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "PAIRING_FAILED=" << error.what() << '\n';
        return 1;
    }
}
