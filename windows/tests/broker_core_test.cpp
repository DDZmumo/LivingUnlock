#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <bcrypt.h>
#include <sddl.h>
#include "../common/broker_protocol.h"
#include "../common/canonical_transcript.h"
#include "../common/phone_messages.h"
#include "../common/pairing_crypto.h"
#include "../common/phone_vault.h"
#include "../broker/session_store.h"
#include "../broker/rfcomm_server.h"
#include <atomic>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>

using namespace lockpin::phone;
void Require(bool value) { if (!value) throw std::runtime_error("Windows phone broker core test failed"); }

class FakeRandom final : public RandomSource {
public:
    bool Fill(std::uint8_t* output, std::size_t size) noexcept override {
        std::lock_guard<std::mutex> lock(mutex_);
        for (std::size_t i = 0; i < size; ++i) output[i] = next_++;
        return true;
    }
private:
    std::uint8_t next_ = 1;
    std::mutex mutex_;
};

void TestFrames() {
    Frame frame;
    frame.type = MessageType::UnlockChallenge;
    frame.requestId = 0x0102030405060708ULL;
    frame.payload = {0, 1, 2, 0xff};
    const auto encoded = EncodeFrame(frame);
    Require(encoded.size() == FrameHeaderSize + frame.payload.size());
    const auto decoded = DecodeFrame(encoded.data(), encoded.size());
    Require(decoded.status == FrameDecodeError::None && decoded.frame.has_value());
    Require(decoded.frame->type == frame.type && decoded.frame->requestId == frame.requestId && decoded.frame->payload == frame.payload);
    const std::vector<std::uint8_t> golden{0x57,0x4c,0x01,0x03,0x00,0x00,0x01,0x02,0x03,0x04,
        0x05,0x06,0x07,0x08,0x00,0x04,0x00,0x01,0x02,0xff};
    Require(encoded == golden);
    auto malformed = encoded;
    malformed[0] = 'X';
    Require(DecodeFrame(malformed.data(), malformed.size()).status == FrameDecodeError::BadMagic);
    malformed = encoded;
    malformed[2] = 2;
    Require(DecodeFrame(malformed.data(), malformed.size()).status == FrameDecodeError::UnsupportedVersion);
    malformed = encoded;
    malformed[3] = 99;
    Require(DecodeFrame(malformed.data(), malformed.size()).status == FrameDecodeError::UnknownMessageType);
    malformed = encoded;
    malformed[14] = 0x1f; malformed[15] = 0xf1;
    Require(DecodeFrame(malformed.data(), malformed.size()).status == FrameDecodeError::PayloadTooLarge);
    malformed = encoded; malformed[5] = 1;
    Require(DecodeFrame(malformed.data(), malformed.size()).status == FrameDecodeError::ReservedFlagsNonZero);
    Require(DecodeFrame(encoded.data(), encoded.size() - 1).status == FrameDecodeError::SizeMismatch);
    Require(DecodeFrame(encoded.data(), FrameHeaderSize - 1).status == FrameDecodeError::TooShort);
    bool rejected = false;
    try { Frame large; large.payload.resize(MaxPayloadSize + 1); EncodeFrame(large); }
    catch (const std::length_error&) { rejected = true; }
    Require(rejected);
}

void TestTranscript() {
    std::vector<std::uint8_t> nonce(16);
    for (std::size_t i = 0; i < nonce.size(); ++i) nonce[i] = static_cast<std::uint8_t>(0xa0 + i);
    const auto transcript = BuildUnlockTranscript("0123456789abcdef", "phone-1",
        0x0102030405060708ULL, nonce, 0x1112131415161718ULL, 30000);
    const std::string prefix("WSLP-V1-UNLOCK-TRANSCRIPT\0", 26);
    Require(transcript.size() == 26 + 1 + 16 + 1 + 7 + 8 + 1 + 16 + 8 + 4);
    Require(std::equal(prefix.begin(), prefix.end(), transcript.begin()));
    Require(transcript[26] == 16 && transcript[43] == 7);
    Require(transcript[51] == 0x01 && transcript[58] == 0x08 && transcript[59] == 16);
    Require(transcript[transcript.size() - 4] == 0x00 && transcript[transcript.size() - 3] == 0x00 &&
        transcript[transcript.size() - 2] == 0x75 && transcript.back() == 0x30);
}

void TestMessagePayloads() {
    PairRequestPayload pairRequest{"phone-1", "Xiaomi 17 Pro", std::vector<std::uint8_t>(32, 0x42), std::vector<std::uint8_t>(65, 0x11)};
    pairRequest.clientPublicKey[0] = 0x04;
    const auto pairRequestBytes = EncodePairRequest(pairRequest);
    const auto decodedPairRequest = DecodePairRequest(pairRequestBytes.data(), pairRequestBytes.size());
    Require(decodedPairRequest && decodedPairRequest->deviceId == pairRequest.deviceId &&
        decodedPairRequest->deviceName == pairRequest.deviceName && decodedPairRequest->pairingToken == pairRequest.pairingToken &&
        decodedPairRequest->clientPublicKey == pairRequest.clientPublicKey);
    auto pairTrailing = pairRequestBytes; pairTrailing.push_back(0);
    Require(!DecodePairRequest(pairTrailing.data(), pairTrailing.size()));
    PairResponsePayload pairResponse{0, std::vector<std::uint8_t>(65, 0x22), std::vector<std::uint8_t>(32, 0x33)};
    pairResponse.serverPublicKey[0] = 0x04;
    const auto pairResponseBytes = EncodePairResponse(pairResponse);
    Require(DecodePairResponse(pairResponseBytes.data(), pairResponseBytes.size()).has_value());
    pairResponse.statusCode = 1; pairResponse.serverPublicKey.clear(); pairResponse.confirmationTag.clear();
    const auto pairRejectedBytes = EncodePairResponse(pairResponse);
    Require(DecodePairResponse(pairRejectedBytes.data(), pairRejectedBytes.size()).has_value());

    UnlockChallengePayload challenge;
    challenge.pcId = "0123456789abcdef";
    challenge.nonce.resize(32); for (std::size_t i = 0; i < challenge.nonce.size(); ++i) challenge.nonce[i] = static_cast<std::uint8_t>(i);
    challenge.timestampMs = 1700000000000ULL; challenge.ttlMs = 30000; challenge.userDisplayName = "Alice";
    const auto encodedChallenge = EncodeUnlockChallenge(challenge);
    const auto decodedChallenge = DecodeUnlockChallenge(encodedChallenge.data(), encodedChallenge.size());
    Require(decodedChallenge && decodedChallenge->pcId == challenge.pcId && decodedChallenge->nonce == challenge.nonce &&
        decodedChallenge->timestampMs == challenge.timestampMs && decodedChallenge->ttlMs == challenge.ttlMs &&
        decodedChallenge->userDisplayName == challenge.userDisplayName);
    auto trailing = encodedChallenge; trailing.push_back(0);
    Require(!DecodeUnlockChallenge(trailing.data(), trailing.size()));
    auto malformedUtf8 = encodedChallenge; malformedUtf8[2] = 0xff;
    Require(!DecodeUnlockChallenge(malformedUtf8.data(), malformedUtf8.size()));

    UnlockResponsePayload response; response.statusCode = 0; response.nonce = challenge.nonce; response.signature.assign(70, 0x30);
    const auto encodedResponse = EncodeUnlockResponse(response);
    const auto decodedResponse = DecodeUnlockResponse(encodedResponse.data(), encodedResponse.size());
    Require(decodedResponse && decodedResponse->statusCode == 0 && decodedResponse->nonce == response.nonce &&
        decodedResponse->signature == response.signature);
    response.statusCode = 1; response.signature.clear();
    const auto rejectedResponse = EncodeUnlockResponse(response);
    Require(DecodeUnlockResponse(rejectedResponse.data(), rejectedResponse.size()).has_value());
    response.signature.push_back(1);
    bool rejected = false; try { EncodeUnlockResponse(response); } catch (const std::invalid_argument&) { rejected = true; }
    Require(rejected);

    UnlockResultPayload result{0, "Windows accepted the unlock request"};
    const auto encodedResult = EncodeUnlockResult(result);
    const auto decodedResult = DecodeUnlockResult(encodedResult.data(), encodedResult.size());
    Require(decodedResult && decodedResult->statusCode == result.statusCode && decodedResult->message == result.message);
    result.statusCode = 3;
    rejected = false; try { EncodeUnlockResult(result); } catch (const std::invalid_argument&) { rejected = true; }
    Require(rejected);
    auto malformedResult = encodedResult; malformedResult.push_back(0);
    Require(!DecodeUnlockResult(malformedResult.data(), malformedResult.size()));

    Require(DecodeCancel(EncodeCancel(1).data(), 1) == 1);
    const std::uint8_t badCancel = 9; Require(!DecodeCancel(&badCancel, 1));
    ErrorPayload error{4, "Frame too large"};
    const auto encodedError = EncodeError(error); const auto decodedError = DecodeError(encodedError.data(), encodedError.size());
    Require(decodedError && decodedError->errorCode == error.errorCode && decodedError->message == error.message);
}

std::vector<std::uint8_t> Hex(const char* value) {
    std::vector<std::uint8_t> result;
    for (std::size_t i = 0; value[i]; i += 2) {
        const auto digit = [](char character) -> unsigned {
            if (character >= '0' && character <= '9') return character - '0';
            if (character >= 'a' && character <= 'f') return character - 'a' + 10;
            throw std::invalid_argument("Invalid hex test vector");
        };
        result.push_back(static_cast<std::uint8_t>((digit(value[i]) << 4) | digit(value[i + 1])));
    }
    return result;
}

void TestPairingCrypto() {
    const auto ikm = Hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
    const auto salt = Hex("000102030405060708090a0b0c");
    const auto expected = Hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
    Require(HkdfSha256(salt, ikm, std::string_view("\xf0\xf1\xf2\xf3\xf4\xf5\xf6\xf7\xf8\xf9", 10), 42) == expected);

    std::vector<std::uint8_t> token(32); for (std::size_t i = 0; i < token.size(); ++i) token[i] = static_cast<std::uint8_t>(i);
    std::vector<std::uint8_t> client(65, 0x11), server(65, 0x22); client[0] = 0x04; server[0] = 0x04;
    const auto key = DerivePairingKey("0123456789abcdef", "phone-1", token);
    const auto transcript = BuildPairConfirmationTranscript("0123456789abcdef", "phone-1", client, server);
    const auto tag = ComputePairConfirmationTag(key, transcript);
    Require(ConstantTimeEqual(tag, tag));
    auto tampered = tag; tampered[0] ^= 1; Require(!ConstantTimeEqual(tag, tampered));
    Require(std::string(transcript.begin(), transcript.begin() + 21) == std::string("WSLP-V1-PAIR-CONFIRM\0", 21));

    const auto vectorToken = Hex("4242424242424242424242424242424242424242424242424242424242424242");
    const auto vectorClient = Hex("046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5");
    const auto vectorServer = Hex("04ff8fd466638dd50665c4fe222678b2e9084e9a51820b460476e96d9fefd788103bd828fb4d9d892480c94814cbe8d901365bfc87d1241007daa66071f40a2dbc");
    const auto vectorKey = DerivePairingKey("0123456789abcdef0123456789abcdef",
        "test-installation-device-id-001", vectorToken);
    Require(std::vector<std::uint8_t>(vectorKey.begin(), vectorKey.end()) ==
        Hex("abd3f5110775c9c8eb7fe34b83755fe3dcdf7d9b177e3bb9f0ccc278d3c992ff"));
    const auto vectorTranscript = BuildPairConfirmationTranscript("0123456789abcdef0123456789abcdef",
        "test-installation-device-id-001", vectorClient, vectorServer);
    Require(vectorTranscript == Hex("57534c502d56312d504149522d434f4e4649524d0000203031323334353637383961626364656630313233343536373839616263646566001f746573742d696e7374616c6c6174696f6e2d6465766963652d69642d3030310041046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5004104ff8fd466638dd50665c4fe222678b2e9084e9a51820b460476e96d9fefd788103bd828fb4d9d892480c94814cbe8d901365bfc87d1241007daa66071f40a2dbc"));
    const auto vectorTag = ComputePairConfirmationTag(vectorKey, vectorTranscript);
    Require(std::vector<std::uint8_t>(vectorTag.begin(), vectorTag.end()) ==
        Hex("987910675225b4d2dea78c530350c32cbb53a99c8c06f9f71426c7103cea7c6b"));
    Require(ValidateP256PublicKey(vectorClient) && ValidateP256PublicKey(vectorServer));
    auto invalidKey = vectorClient; invalidKey.back() ^= 1; Require(!ValidateP256PublicKey(invalidKey));
    const auto generated = GenerateP256PublicKey(); Require(generated.size() == 65 && ValidateP256PublicKey(generated));
}

void TestSignatureVerification() {
    BCRYPT_ALG_HANDLE alg = nullptr;
    Require(BCryptOpenAlgorithmProvider(&alg, BCRYPT_ECDSA_P256_ALGORITHM, nullptr, 0) >= 0);
    BCRYPT_KEY_HANDLE key = nullptr;
    Require(BCryptGenerateKeyPair(alg, &key, 256, 0) >= 0);
    Require(BCryptFinalizeKeyPair(key, 0) >= 0);

    ULONG pubSize = 0;
    Require(BCryptExportKey(key, nullptr, BCRYPT_ECCPUBLIC_BLOB, nullptr, 0, &pubSize, 0) >= 0);
    std::vector<std::uint8_t> pubBlob(pubSize);
    Require(BCryptExportKey(key, nullptr, BCRYPT_ECCPUBLIC_BLOB, pubBlob.data(), pubSize, &pubSize, 0) >= 0);
    std::vector<std::uint8_t> sec1(65); sec1[0] = 0x04;
    std::copy(pubBlob.begin() + sizeof(BCRYPT_ECCKEY_BLOB), pubBlob.end(), sec1.begin() + 1);

    const std::vector<std::uint8_t> data = {0x01, 0x02, 0x03, 0x04, 0x05};
    const auto hash = Sha256(data);
    ULONG sigSize = 0;
    Require(BCryptSignHash(key, nullptr, const_cast<PUCHAR>(hash.data()), static_cast<ULONG>(hash.size()),
        nullptr, 0, &sigSize, 0) >= 0);
    std::vector<std::uint8_t> rawSig(sigSize);
    Require(BCryptSignHash(key, nullptr, const_cast<PUCHAR>(hash.data()), static_cast<ULONG>(hash.size()),
        rawSig.data(), sigSize, &sigSize, 0) >= 0);

    BCryptDestroyKey(key);
    BCryptCloseAlgorithmProvider(alg, 0);

    Require(rawSig.size() == 64);
    const auto encodeInt = [](const std::uint8_t* val, std::size_t len) {
        std::vector<std::uint8_t> res;
        while (len > 1 && *val == 0) { val++; len--; }
        if (*val & 0x80) res.push_back(0);
        res.insert(res.end(), val, val + len);
        return res;
    };
    const auto r = encodeInt(rawSig.data(), 32);
    const auto s = encodeInt(rawSig.data() + 32, 32);
    std::vector<std::uint8_t> der;
    der.push_back(0x30);
    der.push_back(static_cast<std::uint8_t>(2 + r.size() + 2 + s.size()));
    der.push_back(0x02);
    der.push_back(static_cast<std::uint8_t>(r.size()));
    der.insert(der.end(), r.begin(), r.end());
    der.push_back(0x02);
    der.push_back(static_cast<std::uint8_t>(s.size()));
    der.insert(der.end(), s.begin(), s.end());

    Require(VerifyP256Signature(sec1, data, der));

    const std::vector<std::uint8_t> tamperedData = {0x01, 0x02, 0x03, 0x04, 0x06};
    Require(!VerifyP256Signature(sec1, tamperedData, der));

    auto tamperedDer = der;
    tamperedDer.back() ^= 1;
    Require(!VerifyP256Signature(sec1, data, tamperedDer));

    auto wrongSec1 = sec1;
    wrongSec1[1] ^= 1;
    Require(!VerifyP256Signature(wrongSec1, data, der));
}

void TestSessions() {
    FakeRandom random;
    SessionStore store(random);
    UserKey user{}; user[0] = 7;
    const auto created = store.Begin(user, 1000, 30000);
    Require(created.status == BeginStatus::Created && created.challenge.has_value());
    Require(store.Begin(user, 1001).status == BeginStatus::ExistingActiveSession);
    auto wrongNonce = created.challenge->nonce; wrongNonce[0] ^= 0xff;
    Require(store.Approve(created.challenge->requestId, wrongNonce, 1002) == UpdateStatus::NonceMismatch);
    Require(store.Approve(created.challenge->requestId, created.challenge->nonce, 1002) == UpdateStatus::Updated);
    Require(store.Approve(created.challenge->requestId, created.challenge->nonce, 1003) == UpdateStatus::Replay);
    Require(store.Consume(created.challenge->requestId, 1004) == UpdateStatus::Updated);
    Require(store.Consume(created.challenge->requestId, 1005) == UpdateStatus::Replay);
    Require(store.Begin(user, 1006).status == BeginStatus::Created);

    UserKey expiring{}; expiring[0] = 9;
    const auto shortLived = store.Begin(expiring, 2000, 10);
    Require(shortLived.status == BeginStatus::Created);
    Require(store.Approve(shortLived.challenge->requestId, shortLived.challenge->nonce, 2010) == UpdateStatus::Expired);
    store.Prune(2010);
    Require(store.Begin(expiring, 2010, 10).status == BeginStatus::Created);

    UserKey cancelled{}; cancelled[0] = 11;
    const auto cancellable = store.Begin(cancelled, 3000, 100);
    Require(store.Cancel(cancellable.challenge->requestId, 3001) == UpdateStatus::Updated);
    Require(store.Approve(cancellable.challenge->requestId, cancellable.challenge->nonce, 3002) == UpdateStatus::AlreadyFinalized);
    Require(store.Begin(cancelled, 3003, 100).status == BeginStatus::Created);
    Require(store.Begin(cancelled, 3004, 0).status == BeginStatus::InvalidTtl);
}

void TestConcurrentBegin() {
    FakeRandom random;
    SessionStore store(random);
    UserKey user{}; user[0] = 42;
    std::atomic<bool> start{false};
    std::atomic<int> created{0};
    std::vector<std::thread> threads;
    for (int i = 0; i < 12; ++i) threads.emplace_back([&] {
        while (!start.load()) std::this_thread::yield();
        if (store.Begin(user, 5000, 1000).status == BeginStatus::Created) ++created;
    });
    start = true;
    for (auto& thread : threads) thread.join();
    Require(created == 1 && store.Size() == 1);
}

void TestPhoneVault() {
    const std::wstring testDir = L"build\\test_phone_vault";
    CreateDirectoryW(testDir.c_str(), nullptr);

    HANDLE token = nullptr;
    Require(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token));
    DWORD size = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &size);
    std::vector<BYTE> buffer(size);
    Require(GetTokenInformation(token, TokenUser, buffer.data(), size, &size));
    CloseHandle(token);
    LPWSTR strSid = nullptr;
    Require(ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(buffer.data())->User.Sid, &strSid));
    std::wstring sid = strSid;
    LocalFree(strSid);

    PairedDeviceRecord record;
    wcsncpy_s(record.sid, sid.c_str(), _TRUNCATE);
    strcpy_s(record.pcId, "0123456789abcdef");
    strcpy_s(record.deviceId, "phone-test-dev-01");
    strcpy_s(record.deviceName, "Xiaomi Test Phone");
    strcpy_s(record.bluetoothMac, "70:21:7F:C3:1A:1E");
    record.clientPublicKey[0] = 0x04;
    for (std::size_t i = 1; i < 65; ++i) record.clientPublicKey[i] = static_cast<std::uint8_t>(i);
    for (std::size_t i = 0; i < 32; ++i) record.kPair[i] = static_cast<std::uint8_t>(0xaa ^ i);
    record.pairedTimestampSec = 1790000000;
    record.phoneBluetoothAddress = 0x102030405060ULL;

    SavePairedPhone(record, testDir);
    Require(IsPhonePaired(sid, testDir));
    const auto disabledPath = testDir + L"\\phone_" + sid + L".disabled";
    {
        HANDLE marker = CreateFileW(disabledPath.c_str(), GENERIC_WRITE, 0,
            nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr);
        Require(marker != INVALID_HANDLE_VALUE);
        CloseHandle(marker);
    }
    Require(!IsPhonePaired(sid, testDir));
    Require(DeleteFileW(disabledPath.c_str()));
    Require(IsPhonePaired(sid, testDir));

    const auto loaded = LoadPairedPhone(sid, testDir);
    Require(loaded.has_value());
    Require(wcscmp(loaded->sid, sid.c_str()) == 0);
    Require(strcmp(loaded->pcId, "0123456789abcdef") == 0);
    Require(strcmp(loaded->deviceId, "phone-test-dev-01") == 0);
    Require(strcmp(loaded->deviceName, "Xiaomi Test Phone") == 0);
    Require(strcmp(loaded->bluetoothMac, "70:21:7F:C3:1A:1E") == 0);
    Require(memcmp(loaded->clientPublicKey, record.clientPublicKey, 65) == 0);
    Require(memcmp(loaded->kPair, record.kPair, 32) == 0);
    Require(loaded->pairedTimestampSec == 1790000000);
    Require(loaded->version == 2 && loaded->phoneBluetoothAddress == record.phoneBluetoothAddress);

    // Old records retain their original byte layout and have no trusted phone route.
    record.version = 1;
    record.phoneBluetoothAddress = 0;
    SavePairedPhone(record, testDir);
    auto legacy = LoadPairedPhone(sid, testDir);
    Require(legacy && legacy->version == 1 && legacy->phoneBluetoothAddress == 0);
    Require(memcmp(legacy->clientPublicKey, record.clientPublicKey, 65) == 0);
    Require(memcmp(legacy->kPair, record.kPair, 32) == 0);
    legacy->version = 2;
    legacy->phoneBluetoothAddress = 0x102030405060ULL;
    SavePairedPhone(*legacy, testDir);
    const auto migrated = LoadPairedPhone(sid, testDir);
    Require(migrated && migrated->version == 2 && migrated->phoneBluetoothAddress == 0x102030405060ULL);
    Require(memcmp(migrated->kPair, record.kPair, 32) == 0);

    Require(RemovePairedPhone(sid, testDir));
    Require(!IsPhonePaired(sid, testDir));
    Require(!LoadPairedPhone(sid, testDir).has_value());
}

void TestRfcommServerLifecycle() {
    RfcommServer server;
    Require(server.Start(L"WindowsLockPin Test Service"));
    Require(!server.LocalBluetoothAddress().empty());
    server.Stop();
}

int main(int argc, char** argv) {
    try {
        if (argc == 2 && strcmp(argv[1], "--routing-only") == 0) {
            TestPhoneVault();
            Require(ConnectPairedPhone(0, 1000, [] { return false; }) == INVALID_SOCKET);
            Require(ConnectPairedPhone(0x102030405060ULL, 1000, [] { return true; }) == INVALID_SOCKET);
            std::cout << "Phone routing migration and cancellation tests passed\n";
            return 0;
        }
        TestFrames(); TestTranscript(); TestMessagePayloads(); TestPairingCrypto(); TestSignatureVerification();
        TestPhoneVault();
        TestSessions(); TestConcurrentBegin();
        TestRfcommServerLifecycle();
        BcryptRandomSource random;
        std::array<std::uint8_t, 32> bytes{};
        Require(random.Fill(bytes.data(), bytes.size()));
        bool nonZero = false; for (const auto value : bytes) nonZero = nonZero || value != 0;
        Require(nonZero);
        std::cout << "PASS: bounded IPC frames, challenge expiry, cancellation, replay rejection, concurrent session exclusion, CNG RNG, ECDSA P-256 verification, DPAPI phone vault\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
