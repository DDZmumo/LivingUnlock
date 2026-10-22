#pragma once
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include <bcrypt.h>
#include "application_time.h"
#include <future>
#pragma comment(lib,"iphlpapi.lib")

namespace lockpin::phone {
inline constexpr const wchar_t* TimeServers[]={L"ntp.ntsc.ac.cn",L"ntp.aliyun.com",L"ntp.tencent.com",L"time.windows.com"};
struct TimedSample { TimeSample sample; std::uint64_t receivedTick=0; };
inline std::optional<TimeSample> DecodeNtpTime(const std::vector<std::uint8_t>& bytes,
    const std::array<std::uint8_t,8>& nonce,std::uint64_t rtt,std::uint8_t source) {
    if(bytes.size()<48 || rtt>1500 || source>3 || (bytes[0]>>6)==3 || (bytes[0]&7)!=4 ||
        ((bytes[0]>>3)&7)<3 || ((bytes[0]>>3)&7)>4 || bytes[1]<1 || bytes[1]>15 ||
        !std::equal(nonce.begin(),nonce.end(),bytes.begin()+24))return std::nullopt;
    const auto word=[&](std::size_t offset) {
        std::uint64_t value=0;for(std::size_t i=0;i<4;++i)value=(value<<8)|bytes[offset+i];return value;
    };
    const auto timestamp=[&](std::size_t offset) {
        auto seconds=word(offset);if(seconds<2208988800ULL)seconds+=0x100000000ULL;
        return (seconds-2208988800ULL)*1000+((word(offset+4)*1000)>>32);
    };
    const auto received=timestamp(32),sent=timestamp(40);
    if(sent<946684800000ULL || sent>4102444800000ULL || sent<received || sent-received>1000 || sent-received>rtt+5 ||
        (bytes[4]&0x80))return std::nullopt;
    const auto processing=sent-received;
    const auto transit=((rtt>processing?rtt-processing:0)+1)/2;
    const auto uncertainty=(std::max)(1ULL,transit+word(8)*1000/65536+word(4)*1000/65536/2+1);
    if(uncertainty>2000)return std::nullopt;
    return TimeSample{sent+transit,0,static_cast<std::uint32_t>(uncertainty),source};
}
inline std::optional<TimeSample> SelectNtpTime(const std::vector<TimedSample>& input,std::uint64_t now) {
    std::vector<TimeSample> samples;
    for(const auto& timed:input)if(timed.sample.Fresh()) {
        auto sample=timed.sample;
        const auto elapsed=now>=timed.receivedTick?now-timed.receivedTick:0;
        if(elapsed>=TimeCacheMs-sample.ageMs)continue;
        sample.utcMs+=elapsed;sample.ageMs+=static_cast<std::uint32_t>(elapsed);samples.push_back(sample);
    }
    std::optional<TimeSample> best;
    for(std::size_t i=0;i<samples.size();++i) {
        bool agreeing=samples.size()==1;
        for(std::size_t j=0;j<samples.size();++j)if(i!=j) {
            const auto difference=samples[i].utcMs>samples[j].utcMs?samples[i].utcMs-samples[j].utcMs:samples[j].utcMs-samples[i].utcMs;
            if(difference<=(std::max)(1000U,samples[i].uncertaintyMs+samples[j].uncertaintyMs))agreeing=true;
        }
        if(agreeing && (!best || samples[i].source<best->source))best=samples[i];
    }
    return best;
}
inline bool TimeInternetAvailable() {
    // Route lookup sends no packet. NCSI can report offline with a working VPN;
    // a usable route permits bounded probes, and an NTP reply proves reachability.
    DWORD index=0;
    return GetBestInterface(htonl(0x01010101),&index)==NO_ERROR;
}
inline DWORD TimePhysicalInterface() {
    ULONG size=16384;std::vector<std::uint8_t> storage(size);
    auto adapters=reinterpret_cast<IP_ADAPTER_ADDRESSES*>(storage.data());
    auto result=GetAdaptersAddresses(AF_INET,GAA_FLAG_INCLUDE_GATEWAYS,nullptr,adapters,&size);
    if(result==ERROR_BUFFER_OVERFLOW){storage.resize(size);adapters=reinterpret_cast<IP_ADAPTER_ADDRESSES*>(storage.data());
        result=GetAdaptersAddresses(AF_INET,GAA_FLAG_INCLUDE_GATEWAYS,nullptr,adapters,&size);}
    if(result!=NO_ERROR)return 0;
    DWORD chosen=0;ULONG metric=0xffffffffUL;
    for(auto* adapter=adapters;adapter;adapter=adapter->Next) {
        MIB_IF_ROW2 row{};row.InterfaceIndex=adapter->IfIndex;
        if(adapter->OperStatus==IfOperStatusUp && adapter->FirstGatewayAddress && adapter->FirstUnicastAddress &&
            GetIfEntry2(&row)==NO_ERROR && row.InterfaceAndOperStatusFlags.HardwareInterface && adapter->Ipv4Metric<metric) {
            chosen=adapter->IfIndex;metric=adapter->Ipv4Metric;
        }
    }
    return chosen;
}
inline std::optional<TimedSample> QueryNtpTime(std::uint8_t index,bool preferDirect=true) {
    ADDRINFOEXW hints{};hints.ai_family=AF_INET;hints.ai_socktype=SOCK_DGRAM;hints.ai_protocol=IPPROTO_UDP;
    PADDRINFOEXW address=nullptr;
    OVERLAPPED operation{};operation.hEvent=CreateEventW(nullptr,TRUE,FALSE,nullptr);
    if(!operation.hEvent)return std::nullopt;
    struct EventCleanup{HANDLE value;~EventCleanup(){CloseHandle(value);}} closeEvent{operation.hEvent};
    HANDLE lookup=nullptr;
    auto error=GetAddrInfoExW(TimeServers[index],L"123",NS_DNS,nullptr,&hints,&address,nullptr,&operation,nullptr,&lookup);
    if(error==WSA_IO_PENDING) {
        const auto wait=WaitForSingleObject(operation.hEvent,350);
        if(wait!=WAIT_OBJECT_0) {
            GetAddrInfoExCancel(&lookup);
            // The OVERLAPPED and result storage must remain alive until the
            // cancelled resolver completes; cancellation signals this event.
            WaitForSingleObject(operation.hEvent,INFINITE);
            if(address)FreeAddrInfoExW(address);
            return std::nullopt;
        }
        error=GetAddrInfoExOverlappedResult(&operation);
    }
    if(error!=0){if(address)FreeAddrInfoExW(address);return std::nullopt;}
    struct AddressCleanup {PADDRINFOEXW value;~AddressCleanup(){if(value)FreeAddrInfoExW(value);}} freeAddress{address};
    if(!address)return std::nullopt;
    const SOCKET socket=::socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP);
    if(socket==INVALID_SOCKET)return std::nullopt;
    struct SocketCleanup {SOCKET value;~SocketCleanup(){closesocket(value);}} closeSocket{socket};
    const auto target=reinterpret_cast<const sockaddr_in*>(address->ai_addr);
    const auto ip=ntohl(target->sin_addr.s_addr);
    // Fake-IP addresses belong to the TUN resolver and are unusable on a
    // physical interface. Preserve that system route rather than changing DNS.
    if(preferDirect && (ip&0xfffe0000U)!=0xc6120000U) {
        const auto physical=TimePhysicalInterface();
        if(physical) {
            const DWORD networkOrder=htonl(physical);
            setsockopt(socket,IPPROTO_IP,IP_UNICAST_IF,reinterpret_cast<const char*>(&networkOrder),sizeof(networkOrder));
        }
    }
    if(connect(socket,address->ai_addr,static_cast<int>(address->ai_addrlen))==SOCKET_ERROR)return std::nullopt;
    DWORD timeout=650;
    if(setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<const char*>(&timeout),sizeof(timeout))==SOCKET_ERROR)return std::nullopt;
    std::vector<std::uint8_t> request(48,0);request[0]=0x23;
    std::array<std::uint8_t,8> nonce{};
    if(BCryptGenRandom(nullptr,nonce.data(),static_cast<ULONG>(nonce.size()),BCRYPT_USE_SYSTEM_PREFERRED_RNG)<0)return std::nullopt;
    std::copy(nonce.begin(),nonce.end(),request.begin()+40);
    const auto start=TimeTickMs();
    if(send(socket,reinterpret_cast<const char*>(request.data()),static_cast<int>(request.size()),0)!=48)return std::nullopt;
    std::vector<std::uint8_t> reply(512,0);
    const auto length=recv(socket,reinterpret_cast<char*>(reply.data()),static_cast<int>(reply.size()),0);
    const auto end=TimeTickMs();if(length<48)return std::nullopt;reply.resize(static_cast<std::size_t>(length));
    const auto sample=DecodeNtpTime(reply,nonce,end-start,index);
    return sample?std::optional<TimedSample>({*sample,end}):std::nullopt;
}
class NetworkTimeClient {
public:
    bool Refresh(ApplicationTime& clock,bool oneRetry=false) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto now=TimeTickMs();
        if(clock.Snapshot(now).Fresh())return true;
        if((now<nextAttempt_ && !oneRetry) || !TimeInternetAvailable())return false;
        nextAttempt_=now+30000;
        WSADATA wsa{};if(WSAStartup(MAKEWORD(2,2),&wsa)!=0)return false;
        struct WsaCleanup{~WsaCleanup(){WSACleanup();}} cleanup;
        std::vector<std::future<std::optional<TimedSample>>> workers;
        const bool preferDirect=failures_==0;
        for(std::uint8_t i=0;i<3;++i)workers.push_back(std::async(std::launch::async,[i,preferDirect]{return QueryNtpTime(i,preferDirect);}));
        std::vector<TimedSample> samples;
        for(auto& worker:workers){auto value=worker.get();if(value)samples.push_back(*value);}
        auto selected=SelectNtpTime(samples,TimeTickMs());
        if(!selected){const auto fallback=QueryNtpTime(3,preferDirect);if(fallback)selected=SelectNtpTime({*fallback},TimeTickMs());}
        if(selected && clock.Apply(*selected,TimeTickMs())) {
            failures_=0;nextAttempt_=TimeTickMs()+TimeCacheMs;return true;
        }
        failures_=(std::min)(failures_+1,5U);
        nextAttempt_=TimeTickMs()+(std::min)(300000ULL,30000ULL<<(failures_-1));return false;
    }
private:
    std::mutex mutex_;
    std::uint64_t nextAttempt_=0;
    unsigned failures_=0;
};
}
