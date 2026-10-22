#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <windows.h>
#include "time_handshake.h"
#include "application_time_cache.h"
#include <iostream>
#include <stdexcept>
using namespace lockpin::phone;
static void Require(bool value,const char* label){if(!value)throw std::runtime_error(label);}
struct Loopback {
    SOCKET local=INVALID_SOCKET,remote=INVALID_SOCKET;
    Loopback(){
        SOCKET listener=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);Require(listener!=INVALID_SOCKET,"Loopback listener");
        sockaddr_in address{};address.sin_family=AF_INET;address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        Require(bind(listener,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0 && listen(listener,1)==0,"Loopback bind");
        int length=sizeof(address);Require(getsockname(listener,reinterpret_cast<sockaddr*>(&address),&length)==0,"Loopback address");
        local=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);Require(local!=INVALID_SOCKET,"Loopback client");
        Require(connect(local,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0,"Loopback connect");
        remote=accept(listener,nullptr,nullptr);closesocket(listener);Require(remote!=INVALID_SOCKET,"Loopback accept");
    }
    ~Loopback(){if(local!=INVALID_SOCKET)closesocket(local);if(remote!=INVALID_SOCKET)closesocket(remote);}
};
static void HandshakeCase(unsigned mode,bool dropCommitAck=false) {
    Loopback sockets;ApplicationTimeEngine engine;
    const std::vector<std::uint8_t> key(32,42),nonce(32,5);
    unsigned fetches=0,commits=0,probes=0;
    std::exception_ptr failure;
    std::thread peer([&]{try{
        while(true) {
            std::vector<std::uint8_t> wire;
            Require(ReceiveFrameUntil(sockets.remote,wire,GetTickCount64()+6000),"Peer receives time packet");
            const auto frame=DecodeFrame(wire.data(),wire.size()).frame;Require(frame.has_value(),"Time frame");
            const auto packet=DecodeTimePacket(frame->payload,key);Require(packet.has_value(),"Time packet MAC");
            auto reply=*packet;
            const auto now=SystemUtcMs();
            reply.sample={now-(mode==3?0:29400),0,0,SystemTimeSource};
            if(mode==0)reply.sample={now+29400,0,10,0};
            if(packet->kind==TimeKind::Probe) { ++probes;reply.kind=TimeKind::ProbeReply; }
            else if(packet->kind==TimeKind::Calibrate)reply.kind=TimeKind::SampleReply;
            else if(packet->kind==TimeKind::Commit) {
                ++commits;
                Require(packet->sample.Fresh()==(mode<2),"Commit shares network calibration or stays independent");
                if(dropCommitAck && commits==1)continue;
                reply.kind=TimeKind::CommitReply;
            } else throw std::runtime_error("Unexpected time phase");
            Require(SendAll(sockets.remote,EncodeFrame({MessageType::Ping,packet->requestId,EncodeTimePacket(reply,key)})),"Peer sends time reply");
            if(packet->kind==TimeKind::Commit)break;
        }
    }catch(...){failure=std::current_exception();shutdown(sockets.remote,SD_BOTH);}});
    const bool proceed=RunTimeHandshake(sockets.local,engine,key,99,nonce,[]{return false;},[](const wchar_t*){},
        [&](bool retry){++fetches;Require(fetches<=2 && retry==(fetches==2),"At most one time fetch retry");
            return mode==1 && engine.clock.Apply({SystemUtcMs(),0,10,1},TimeTickMs());});
    peer.join();if(failure)std::rethrow_exception(failure);
    Require(proceed,"Time failure must not block unlock");
    Require(probes==1 && commits==(dropCommitAck?2U:1U),"Handshake and commit retry budgets");
    Require(fetches==(mode==1?1U:mode==2?2U:0U),"Phone first, PC fallback, offline fallback, and no fetch for matching clocks");
}
static void LateTimeReplyCase() {
    Loopback sockets;std::vector<std::uint8_t> key(32,42);
    TimePacket packet;packet.sample={SystemUtcMs(),0,0,SystemTimeSource};
    const auto late=EncodeFrame({MessageType::Ping,1,EncodeTimePacket(packet,key)});
    Require(SendAll(sockets.remote,late) && SendAll(sockets.remote,late),"Queue late time replies");
    const auto response=EncodeFrame({MessageType::UnlockResponse,1,{1,2,3}});
    Require(SendAll(sockets.remote,response),"Queue unlock response");
    std::vector<std::uint8_t> received;
    Require(ReceiveUnlockFrameUntil(sockets.local,received,GetTickCount64()+500) && received==response,
        "Late time replies must not consume the actual unlock response");
    FrameStreamReader reader;
    Require(send(sockets.remote,reinterpret_cast<const char*>(late.data()),8,0)==8,"Partial time header");
    Require(!reader.ReceiveUntil(sockets.local,received,GetTickCount64()+60),"Partial time frame times out");
    Require(SendAll(sockets.remote,{late.begin()+8,late.end()}) && SendAll(sockets.remote,response),"Complete late time frame after timeout");
    Require(ReceiveUnlockFrameUntil(sockets.local,received,GetTickCount64()+500,&reader) && received==response,
        "Partial-frame timeouts must preserve framing when falling back to unlock");
}
static void UnsupportedHandshakeCase() {
    Loopback sockets;ApplicationTimeEngine engine;
    const std::vector<std::uint8_t> key(32,42),nonce(32,9);
    unsigned probes=0,fetches=0;std::exception_ptr failure;
    std::thread peer([&]{try {
        for(unsigned i=0;i<2;++i) {
            std::vector<std::uint8_t> wire;
            Require(ReceiveFrameUntil(sockets.remote,wire,GetTickCount64()+4000),"Legacy peer receives probe");
            const auto frame=DecodeFrame(wire.data(),wire.size()).frame;
            Require(frame && frame->type==MessageType::Pong,"Legacy peer ignores optional PONG");++probes;
        }
        std::vector<std::uint8_t> wire;
        Require(ReceiveFrameUntil(sockets.remote,wire,GetTickCount64()+4000),"Unlock follows failed time preparation");
        const auto frame=DecodeFrame(wire.data(),wire.size()).frame;
        Require(frame && frame->type==MessageType::UnlockChallenge,"No third probe; proceed to unlock");
    } catch(...){failure=std::current_exception();shutdown(sockets.remote,SD_BOTH);}});
    FrameStreamReader reader;
    const bool proceed=RunTimeHandshake(sockets.local,engine,key,55,nonce,[]{return false;},[](const wchar_t*){},
        [&](bool){++fetches;return false;},&reader);
    SendAll(sockets.local,EncodeFrame({MessageType::UnlockChallenge,55,{}}));
    peer.join();if(failure)std::rethrow_exception(failure);
    Require(proceed && probes==2 && fetches==0,"Unsupported handshake falls back after one retry");
}
static std::vector<std::uint8_t> NtpReply(const std::array<std::uint8_t,8>& nonce,std::uint64_t utc) {
    std::vector<std::uint8_t> bytes(48,0);bytes[0]=0x24;bytes[1]=2;
    std::copy(nonce.begin(),nonce.end(),bytes.begin()+24);
    const auto stamp=[&](std::size_t offset){
        const auto seconds=utc/1000+2208988800ULL;
        const auto fraction=((utc%1000)<<32)/1000;
        for(unsigned i=0;i<4;++i){bytes[offset+3-i]=static_cast<std::uint8_t>(seconds>>(i*8));bytes[offset+7-i]=static_cast<std::uint8_t>(fraction>>(i*8));}
    };
    stamp(32);stamp(40);return bytes;
}
int main(int argc,char** argv) {
    try {
        constexpr std::uint64_t epoch=1700000000000ULL;
        ApplicationTime clock(epoch,100);
        Require(clock.Snapshot(600).utcMs==epoch+500,"System-seeded monotonic clock");
        Require(clock.Apply({epoch+30000,100,10,0},600),"Apply calibrated time");
        Require(clock.Snapshot(1600).utcMs==epoch+31000 && clock.Snapshot(1600).ageMs==1100,"Cache age survives transfers");
        Require(!clock.Snapshot(600600).Fresh() && clock.Snapshot(600600).utcMs==epoch+630000,"Expired sample keeps free-running internally");
        Require(!clock.Apply({epoch,TimeCacheMs,10,0},0),"Reject expired peer sample");
        std::vector<std::uint8_t> key(32,42);
        TimePacket packet;packet.sample={epoch,0,0,SystemTimeSource};packet.requestId=0x0102030405060708ULL;
        auto encoded=EncodeTimePacket(packet,key);
        const auto decoded=DecodeTimePacket(encoded,key);
        const std::string golden="4c55544d0100ff00010203040506070800000000000000000000000000000000000000000000000000000000000000000000018bcfe56800000000000000000000000000da15aa68c04a09e239b484f450e98542a47ecf91a3732f579388ded0cea77560";
        std::string actual;const char* hex="0123456789abcdef";
        for(const auto byte:encoded){actual+=hex[byte>>4];actual+=hex[byte&15];}
        Require(actual==golden,"Cross-language signed wire vector");
        Require(decoded && decoded->requestId==packet.requestId && decoded->sample.utcMs==epoch,"Authenticated time packet roundtrip");
        for(std::size_t i=0;i<encoded.size();++i) {
            auto corrupt=encoded;corrupt[i]^=1;
            Require(!DecodeTimePacket(corrupt,key),"Every authenticated byte must reject tampering");
        }
        Require(!DecodeTimePacket(encoded,std::vector<std::uint8_t>(32,43)),"Reject wrong pairing key");
        std::array<std::uint8_t,8> nonce{1,2,3,4,5,6,7,8};
        auto bytes=NtpReply(nonce,epoch);
        auto ntp=DecodeNtpTime(bytes,nonce,100,0);
        Require(ntp && ntp->utcMs==epoch+50,"NTP timestamp uses server time plus monotonic RTT");
        bytes[24]^=1;Require(!DecodeNtpTime(bytes,nonce,100,0),"Reject unrelated NTP reply");bytes[24]^=1;
        bytes[1]=0;Require(!DecodeNtpTime(bytes,nonce,100,0),"Reject Kiss-of-Death response");bytes[1]=2;
        bytes[0]=0xe4;Require(!DecodeNtpTime(bytes,nonce,100,0),"Reject unsynchronized time server");
        auto chosen=SelectNtpTime({{{epoch,0,10,0},0},{{epoch+20,0,10,1},0},{{epoch+10,0,10,2},0}},0);
        Require(chosen && chosen->source==0,"Prefer NTSC among consistent parallel results");
        chosen=SelectNtpTime({{{epoch+10000,0,10,0},0},{{epoch+20,0,10,1},0},{{epoch+10,0,10,2},0}},0);
        Require(chosen && chosen->source==1,"Reject primary outlier rather than average it");
        Require(!SelectNtpTime({{{epoch,0,10,0},0},{{epoch+10000,0,10,1},0}},0),"Reject conflicting pair");
        WSADATA wsa{};Require(WSAStartup(MAKEWORD(2,2),&wsa)==0,"Winsock startup");
        HandshakeCase(0);HandshakeCase(1);HandshakeCase(2);HandshakeCase(3);HandshakeCase(0,true);
        LateTimeReplyCase();UnsupportedHandshakeCase();WSACleanup();
        if(argc>1 && std::string(argv[1])=="--live") {
            ApplicationTime live(SystemUtcMs(),TimeTickMs());NetworkTimeClient client;
            std::cout<<"LIVE_NETWORK_ROUTE="<<TimeInternetAvailable()<<'\n';
            const auto start=TimeTickMs();const bool success=client.Refresh(live);
            std::cout<<"LIVE_NTP success="<<success<<" source="<<static_cast<unsigned>(live.Snapshot(TimeTickMs()).source)
                <<" elapsedMs="<<TimeTickMs()-start<<'\n';
        }
        if(argc>2 && std::string(argv[1])=="--cache") {
            const int size=MultiByteToWideChar(CP_UTF8,0,argv[2],-1,nullptr,0);
            std::wstring sid(static_cast<std::size_t>(size),L'\0');MultiByteToWideChar(CP_UTF8,0,argv[2],-1,sid.data(),size);
            sid.resize(static_cast<std::size_t>(size-1));
            const auto cached=ReadSharedApplicationTime(sid);
            Require(cached.has_value(),"Native login component can import desktop clock cache");
            const auto now=SystemUtcMs();const auto difference=now>cached->utcMs?now-cached->utcMs:cached->utcMs-now;
            Require(difference<2000,"Shared desktop clock projection matches current calibrated UTC");
            std::cout<<"SHARED_CACHE_OK source="<<static_cast<unsigned>(cached->source)<<" ageMs="<<cached->ageMs<<'\n';
        }
        std::cout<<"PASS: monotonic baseline, cache age, authenticated wire, NTP validation and source selection\n";
    } catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}
}
