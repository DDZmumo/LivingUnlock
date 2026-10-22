#pragma once
#include "network_time.h"
#include "broker_protocol.h"
#include "rfcomm_server.h"
#include <atomic>
#include <condition_variable>
#include <functional>
#include <thread>

namespace lockpin::phone {
class FrameStreamReader {
public:
    bool ReceiveUntil(SOCKET socket,std::vector<std::uint8_t>& frame,std::uint64_t deadline) {
        while(GetTickCount64()<deadline) {
            if(pending_.size()>=FrameHeaderSize) {
                const auto payload=(static_cast<std::size_t>(pending_[14])<<8)|pending_[15];
                if(payload>MaxPayloadSize)return false;
                const auto size=FrameHeaderSize+payload;
                if(pending_.size()>=size) {
                    frame.assign(pending_.begin(),pending_.begin()+size);
                    pending_.erase(pending_.begin(),pending_.begin()+size);
                    return true;
                }
            }
            const auto now=GetTickCount64();if(now>=deadline)return false;
            const DWORD timeout=static_cast<DWORD>((std::min)(deadline-now,60000ULL));
            if(setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<const char*>(&timeout),sizeof(timeout))==SOCKET_ERROR)return false;
            std::array<std::uint8_t,4096> bytes{};
            const auto size=recv(socket,reinterpret_cast<char*>(bytes.data()),static_cast<int>(bytes.size()),0);
            if(size<=0)return false; // Preserve partial bytes on a timeout.
            if(pending_.size()+static_cast<std::size_t>(size)>MaxFrameSize*2)return false;
            pending_.insert(pending_.end(),bytes.begin(),bytes.begin()+size);
        }
        return false;
    }
private:
    std::vector<std::uint8_t> pending_;
};
inline std::uint64_t SystemUtcMs() {
    return static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count());
}
class ApplicationTimeEngine {
public:
    ApplicationTime clock{SystemUtcMs(),TimeTickMs()};
    NetworkTimeClient network;
    ~ApplicationTimeEngine(){Stop();}
    void Start() {
        if(worker_.joinable())return;
        worker_=std::thread([this] {
            try {
                // Wait for connectivity without consuming any of the three attempts.
                for(unsigned attempt=0;attempt<3 && !stopping_;) {
                    if(clock.Snapshot(TimeTickMs()).Fresh())return;
                    if(TimeInternetAvailable()) {
                        ++attempt;
                        if(network.Refresh(clock))return;
                        if(attempt==3)return;
                    }
                    std::unique_lock<std::mutex> lock(mutex_);
                    cv_.wait_for(lock,std::chrono::seconds(attempt==0?10:(attempt==1?30:60)),[this]{return stopping_.load();});
                }
            } catch (...) { /* Time service failure never affects authentication. */ }
        });
    }
    void Stop(){stopping_=true;cv_.notify_all();if(worker_.joinable())worker_.join();}
private:
    std::atomic<bool> stopping_{false};
    std::mutex mutex_;
    std::condition_variable cv_;
    std::thread worker_;
};

// Optional authenticated PONG/PING extension. Older clients ignore the probe;
// no challenge exists yet, so this phase cannot consume its authentication TTL.
inline bool RunTimeHandshake(SOCKET socket,ApplicationTimeEngine& engine,
    const std::vector<std::uint8_t>& key,std::uint64_t requestId,const std::vector<std::uint8_t>& nonce,
    const std::function<bool()>& cancelled,const std::function<void(const wchar_t*)>& status,
    const std::function<bool(bool)>& refresh={},FrameStreamReader* sharedReader=nullptr) {
    FrameStreamReader localReader;
    auto& reader=sharedReader?*sharedReader:localReader;
    TimePacket packet;packet.requestId=requestId;
    if(nonce.size()!=packet.nonce.size())return true;
    std::copy(nonce.begin(),nonce.end(),packet.nonce.begin());
    DWORD previousSendTimeout=20000;int optionSize=sizeof(previousSendTimeout);
    getsockopt(socket,SOL_SOCKET,SO_SNDTIMEO,reinterpret_cast<char*>(&previousSendTimeout),&optionSize);
    struct RestoreSendTimeout {SOCKET socket;DWORD value;~RestoreSendTimeout(){
        setsockopt(socket,SOL_SOCKET,SO_SNDTIMEO,reinterpret_cast<const char*>(&value),sizeof(value));
    }} restore{socket,previousSendTimeout};
    const DWORD sendTimeout=1200;
    setsockopt(socket,SOL_SOCKET,SO_SNDTIMEO,reinterpret_cast<const char*>(&sendTimeout),sizeof(sendTimeout));
    bool userCancelled=false;
    const auto send=[&](TimeKind kind,std::uint32_t duration=0) {
        packet.kind=kind;packet.sample=engine.clock.Snapshot(TimeTickMs());packet.durationMs=duration;
        return !cancelled() && SendAll(socket,EncodeFrame({MessageType::Pong,requestId,EncodeTimePacket(packet,key)}));
    };
    const auto receive=[&](TimeKind expected,std::uint32_t timeout)->std::optional<TimePacket> {
        const auto deadline=GetTickCount64()+timeout;
        for(unsigned skipped=0;skipped<6;++skipped) {
            std::vector<std::uint8_t> bytes;
            if(!reader.ReceiveUntil(socket,bytes,deadline))return std::nullopt;
            const auto frame=DecodeFrame(bytes.data(),bytes.size()).frame;
            if(!frame || frame->requestId!=requestId)continue;
            if(frame->type==MessageType::Cancel){userCancelled=true;return std::nullopt;}
            if(frame->type!=MessageType::Ping)continue;
            const auto response=DecodeTimePacket(frame->payload,key);
            if(response && response->kind==expected && response->requestId==requestId && response->nonce==packet.nonce)return response;
        }
        return std::nullopt;
    };
    try {
        status(L"正在与手机交换内部时间...");
        auto sent=TimeTickMs();
        std::optional<TimePacket> response;
        for(unsigned attempt=0;attempt<2 && !response && !cancelled();++attempt) {
            sent=TimeTickMs();
            if(send(TimeKind::Probe))response=receive(TimeKind::ProbeReply,1200);
            if(userCancelled)return false;
        }
        if(!response){status(L"时间握手未完成，使用独立计时继续解锁。");return !userCancelled && !cancelled();}
        const auto rtt=TimeTickMs()-sent;
        auto transit=static_cast<std::uint32_t>((std::min)(rtt/2,1000ULL));
        const auto local=engine.clock.Snapshot(TimeTickMs());
        const auto peerUtc=response->sample.utcMs+transit;
        const auto difference=local.utcMs>peerUtc?local.utcMs-peerUtc:peerUtc-local.utcMs;
        const bool needsCalibration=difference>TimeDifferenceMs+local.uncertaintyMs+response->sample.uncertaintyMs+transit;
        if(response->sample.Fresh())engine.clock.Apply(response->sample,TimeTickMs(),transit);
        else if(needsCalibration && !local.Fresh()) {
            status(L"内部时间偏差较大，等待手机取时...");
            auto requested=TimeTickMs();
            response.reset();
            for(unsigned attempt=0;attempt<2 && !response && !cancelled();++attempt) {
                requested=TimeTickMs();
                if(send(TimeKind::Calibrate))response=receive(TimeKind::SampleReply,6500);
                if(userCancelled)return false;
            }
            if(userCancelled || cancelled())return false;
            if(response && response->kind==TimeKind::SampleReply) {
                const auto elapsed=TimeTickMs()-requested;
                const auto transport=elapsed>response->durationMs?elapsed-response->durationMs:0;
                transit=static_cast<std::uint32_t>((std::min)(transport/2,1000ULL));
                if(response->sample.Fresh())engine.clock.Apply(response->sample,TimeTickMs(),transit);
            }
            if(!engine.clock.Snapshot(TimeTickMs()).Fresh()) {
                status(L"手机未取得有效时间，电脑正在尝试取时...");
                for(unsigned attempt=0;attempt<2 && !cancelled();++attempt)
                    if(refresh?refresh(attempt==1):engine.network.Refresh(engine.clock,attempt==1))break;
            }
        }
        bool committed=false;
        for(unsigned attempt=0;attempt<2 && !cancelled();++attempt) {
            if(send(TimeKind::Commit,transit) && receive(TimeKind::CommitReply,800)){committed=true;break;}
            if(userCancelled)return false;
        }
        status(!committed?L"同步未确认，使用独立计时继续解锁。":
            engine.clock.Snapshot(TimeTickMs()).Fresh()?L"内部时间已校准，正在生成解锁请求...":L"使用独立计时，正在生成解锁请求...");
    } catch (...) { status(L"校时未完成，使用独立计时继续解锁。"); }
    return !userCancelled && !cancelled();
}
inline bool ReceiveUnlockFrameUntil(SOCKET socket,std::vector<std::uint8_t>& wire,std::uint64_t deadline,FrameStreamReader* reader=nullptr) {
    // Late retry replies are not unlock responses. Never let them consume the
    // next stage's first response or reset its absolute deadline.
    for(unsigned skipped=0;skipped<12;++skipped) {
        if(!(reader?reader->ReceiveUntil(socket,wire,deadline):ReceiveFrameUntil(socket,wire,deadline)))return false;
        const auto frame=DecodeFrame(wire.data(),wire.size()).frame;
        if(!frame || frame->type!=MessageType::Ping || !IsTimePacket(frame->payload))return true;
    }
    return false;
}
}
