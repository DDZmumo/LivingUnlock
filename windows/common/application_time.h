#pragma once
#include <windows.h>
#include "pairing_crypto.h"
#include <algorithm>
#include <chrono>
#include <mutex>
#include <optional>
#include <stdexcept>

namespace lockpin::phone {
constexpr std::uint32_t TimeCacheMs = 600000;
constexpr std::uint32_t TimeDifferenceMs = 2000;
constexpr std::uint8_t SystemTimeSource = 255;
inline std::uint64_t TimeTickMs() {
    return GetTickCount64(); // Includes suspended time, like Android elapsedRealtime.
}
struct TimeSample {
    std::uint64_t utcMs = 0;
    std::uint32_t ageMs = 0;
    std::uint32_t uncertaintyMs = 0;
    std::uint8_t source = SystemTimeSource;
    bool Fresh() const { return source < 4 && ageMs < TimeCacheMs && uncertaintyMs <= 2000; }
};
// The system clock is read only by the constructor. All subsequent progression
// is monotonic, including after a calibration sample becomes too old to share.
class ApplicationTime {
public:
    ApplicationTime(std::uint64_t utcMs, std::uint64_t tickMs) : sample_{utcMs,0,0,SystemTimeSource}, tick_(tickMs) {}
    TimeSample Snapshot(std::uint64_t now) const {
        std::lock_guard<std::mutex> lock(mutex_);
        auto out = sample_;
        const auto elapsed = now >= tick_ ? now-tick_ : 0;
        out.utcMs += elapsed;
        const auto age = static_cast<std::uint64_t>(out.ageMs) + elapsed;
        if (out.source < 4 && age < TimeCacheMs) out.ageMs = static_cast<std::uint32_t>(age);
        else { out.source=SystemTimeSource; out.ageMs=0; out.uncertaintyMs=0; }
        return out;
    }
    bool Apply(TimeSample sample, std::uint64_t now, std::uint32_t transitMs=0) {
        if (!sample.Fresh() || transitMs > 1000 || sample.ageMs+transitMs >= TimeCacheMs ||
            sample.uncertaintyMs+transitMs > 2000) return false;
        sample.utcMs += transitMs;
        sample.ageMs += transitMs;
        sample.uncertaintyMs += transitMs;
        std::lock_guard<std::mutex> lock(mutex_);
        sample_=sample; tick_=now;
        return true;
    }
private:
    mutable std::mutex mutex_;
    TimeSample sample_;
    std::uint64_t tick_;
};

enum class TimeKind : std::uint8_t { Probe=0, ProbeReply=1, Calibrate=2, SampleReply=3, Commit=4, CommitReply=5 };
struct TimePacket {
    TimeKind kind = TimeKind::Probe;
    std::uint64_t requestId = 0;
    std::array<std::uint8_t,32> nonce{};
    TimeSample sample;
    std::uint32_t durationMs = 0;
};
inline bool IsTimePacket(const std::vector<std::uint8_t>& bytes) {
    return bytes.size()>=4 && std::equal(bytes.begin(),bytes.begin()+4,"LUTM");
}
inline Sha256Digest TimeMac(const std::vector<std::uint8_t>& key,const std::vector<std::uint8_t>& body) {
    const std::string_view domain="LivingUnlock application time v1";
    auto derived=HmacSha256(key,{domain.begin(),domain.end()});
    const auto mac=HmacSha256({derived.begin(),derived.end()},body);
    std::fill(derived.begin(),derived.end(),static_cast<std::uint8_t>(0));
    return mac;
}
inline std::vector<std::uint8_t> EncodeTimePacket(const TimePacket& packet,const std::vector<std::uint8_t>& key) {
    if(key.size()!=32)throw std::invalid_argument("Invalid time authentication key");
    std::vector<std::uint8_t> out(68,0);
    out[0]='L';out[1]='U';out[2]='T';out[3]='M';out[4]=1;
    out[5]=static_cast<std::uint8_t>(packet.kind);out[6]=packet.sample.source;
    const auto put=[&](std::size_t offset,std::uint64_t value,std::size_t size) {
        for(std::size_t i=0;i<size;++i)out[offset+size-1-i]=static_cast<std::uint8_t>(value>>(i*8));
    };
    put(8,packet.requestId,8);std::copy(packet.nonce.begin(),packet.nonce.end(),out.begin()+16);
    put(48,packet.sample.utcMs,8);put(56,packet.sample.ageMs,4);
    put(60,packet.sample.uncertaintyMs,4);put(64,packet.durationMs,4);
    const auto mac=TimeMac(key,out);out.insert(out.end(),mac.begin(),mac.end());return out;
}
inline std::optional<TimePacket> DecodeTimePacket(const std::vector<std::uint8_t>& bytes,
    const std::vector<std::uint8_t>& key) {
    if(bytes.size()!=100 || !IsTimePacket(bytes) || bytes[4]!=1 || bytes[5]>5 || bytes[7]!=0 || key.size()!=32)return std::nullopt;
    Sha256Digest received{};std::copy(bytes.begin()+68,bytes.end(),received.begin());
    if(!ConstantTimeEqual(received,TimeMac(key,{bytes.begin(),bytes.begin()+68})))return std::nullopt;
    const auto get=[&](std::size_t offset,std::size_t size) {
        std::uint64_t value=0;for(std::size_t i=0;i<size;++i)value=(value<<8)|bytes[offset+i];return value;
    };
    TimePacket packet;packet.kind=static_cast<TimeKind>(bytes[5]);packet.sample.source=bytes[6];
    packet.requestId=get(8,8);std::copy(bytes.begin()+16,bytes.begin()+48,packet.nonce.begin());
    packet.sample.utcMs=get(48,8);packet.sample.ageMs=static_cast<std::uint32_t>(get(56,4));
    packet.sample.uncertaintyMs=static_cast<std::uint32_t>(get(60,4));packet.durationMs=static_cast<std::uint32_t>(get(64,4));
    if(packet.sample.utcMs<946684800000ULL || packet.sample.utcMs>4102444800000ULL || packet.durationMs>4000)return std::nullopt;
    if(packet.sample.source==SystemTimeSource) {
        if(packet.sample.ageMs || packet.sample.uncertaintyMs)return std::nullopt;
    } else if(!packet.sample.Fresh())return std::nullopt;
    return packet;
}
}
