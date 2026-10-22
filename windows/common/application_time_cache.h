#pragma once
#include "application_time.h"
#include <sddl.h>

namespace lockpin::phone {
inline std::optional<DWORD> TimeBootId() {
    DWORD value=0,size=sizeof(value);
    if(RegGetValueW(HKEY_LOCAL_MACHINE,L"SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Memory Management\\PrefetchParameters",
        L"BootId",RRF_RT_REG_DWORD,nullptr,&value,&size)!=ERROR_SUCCESS)return std::nullopt;
    return value;
}
class TimeCacheGuard {
public:
    explicit TimeCacheGuard(const std::wstring& sid) {
        PSECURITY_DESCRIPTOR descriptor=nullptr;
        const auto sddl=L"D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;"+sid+L")";
        if(!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(),SDDL_REVISION_1,&descriptor,nullptr))return;
        SECURITY_ATTRIBUTES attributes{sizeof(SECURITY_ATTRIBUTES),descriptor,FALSE};
        handle_=CreateMutexW(&attributes,FALSE,(L"Local\\LivingUnlock.ApplicationTime."+sid).c_str());
        LocalFree(descriptor);
        if(handle_) {const auto wait=WaitForSingleObject(handle_,20);locked_=wait==WAIT_OBJECT_0 || wait==WAIT_ABANDONED;}
    }
    ~TimeCacheGuard(){if(locked_)ReleaseMutex(handle_);if(handle_)CloseHandle(handle_);}
    explicit operator bool() const {return locked_;}
private:
    HANDLE handle_=nullptr;
    bool locked_=false;
};
inline std::optional<TimeSample> ReadSharedApplicationTime(const std::wstring& sid) {
    const auto boot=TimeBootId();if(!boot)return std::nullopt;
    TimeCacheGuard guard(sid);if(!guard)return std::nullopt;
    HKEY key=nullptr;
    if(RegOpenKeyExW(HKEY_USERS,(sid+L"\\Software\\LivingUnlock\\ApplicationTime").c_str(),0,KEY_QUERY_VALUE|KEY_WOW64_64KEY,&key)!=ERROR_SUCCESS)return std::nullopt;
    struct CloseKey{HKEY value;~CloseKey(){RegCloseKey(value);}} close{key};
    const auto read=[&](const wchar_t* name,std::uint64_t& value) {
        DWORD size=sizeof(value);return RegGetValueW(key,nullptr,name,RRF_RT_REG_QWORD,nullptr,&value,&size)==ERROR_SUCCESS;
    };
    std::uint64_t version=0,storedBoot=0,tick=0,utc=0,age=0,uncertainty=0,source=255;
    if(!read(L"Version",version)||version!=1||!read(L"BootId",storedBoot)||storedBoot!=*boot||
        !read(L"ReferenceTick",tick)||!read(L"UtcMs",utc)||!read(L"AgeMs",age)||
        !read(L"UncertaintyMs",uncertainty)||!read(L"Source",source))return std::nullopt;
    const auto now=TimeTickMs();
    if(tick>now || now-tick>=TimeCacheMs || age>=TimeCacheMs-(now-tick) || source>3 || uncertainty>2000 ||
        utc<946684800000ULL || utc>4102444800000ULL)return std::nullopt;
    return TimeSample{utc+(now-tick),static_cast<std::uint32_t>(age+now-tick),static_cast<std::uint32_t>(uncertainty),static_cast<std::uint8_t>(source)};
}
inline bool WriteSharedApplicationTime(const std::wstring& sid,const TimeSample& sample) {
    const auto boot=TimeBootId();if(!boot || !sample.Fresh())return false;
    TimeCacheGuard guard(sid);if(!guard)return false;
    HKEY key=nullptr;
    if(RegCreateKeyExW(HKEY_USERS,(sid+L"\\Software\\LivingUnlock\\ApplicationTime").c_str(),0,nullptr,0,KEY_SET_VALUE|KEY_WOW64_64KEY,nullptr,&key,nullptr)!=ERROR_SUCCESS)return false;
    const auto write=[&](const wchar_t* name,std::uint64_t value){return RegSetValueExW(key,name,0,REG_QWORD,reinterpret_cast<const BYTE*>(&value),sizeof(value))==ERROR_SUCCESS;};
    std::uint64_t revision=0;DWORD revisionSize=sizeof(revision);
    RegGetValueW(key,nullptr,L"Revision",RRF_RT_REG_QWORD,nullptr,&revision,&revisionSize);
    const bool ok=write(L"Version",1)&&write(L"BootId",*boot)&&write(L"ReferenceTick",TimeTickMs())&&write(L"UtcMs",sample.utcMs)&&
        write(L"AgeMs",sample.ageMs)&&write(L"UncertaintyMs",sample.uncertaintyMs)&&write(L"Source",sample.source)&&
        write(L"Revision",revision<0x7fffffffffffffffULL?revision+1:1);
    RegCloseKey(key);return ok;
}
}
