using System.Security.Principal;
using Microsoft.Win32;

namespace LivingUnlock.Windows.Services;

public sealed record AppTimeSample(long UtcMs, long Tick, long AgeMs=0, long UncertaintyMs=0, int Source=255)
{
    public bool Fresh => Source is >=0 and <4 && AgeMs is >=0 and <600000 && UncertaintyMs is >=0 and <=2000;
    public AppTimeSample At(long now) => this with { UtcMs=UtcMs+Math.Max(0,now-Tick),
        AgeMs=AgeMs+Math.Max(0,now-Tick), Tick=now };
}

// Auxiliary application time only. This user-scoped cache never grants login,
// changes system UTC, changes TOTP verification, or extends a challenge deadline.
public static class ApplicationTimeService
{
    private const string CachePath=@"Software\LivingUnlock\ApplicationTime";
    private static readonly string Sid=WindowsIdentity.GetCurrent().User!.Value;
    private static readonly object Gate=new();
    private static readonly CancellationTokenSource Lifetime=new();
    private static AppTimeSample _reference=new(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),Environment.TickCount64);
    private static long _observedRevision;
    private static Task<bool>? _pending;
    private static bool _started;
    public static event Action? Changed;

    public static AppTimeSample Snapshot()
    {
        TryImport();
        lock(Gate)return _reference.At(Environment.TickCount64);
    }
    public static bool IsCalibrating {get {lock(Gate)return _pending is {IsCompleted:false};}}
    public static string SourceLabel(AppTimeSample sample) => sample.Fresh
        ? new[]{"国家授时中心","阿里云","腾讯云","Windows 时间服务器"}[sample.Source] : "独立计时";

    public static void Start()
    {
        lock(Gate){if(_started)return;_started=true;}
        _=Task.Run(async () => {
            try {
                for(int attempt=0;attempt<3;attempt++) {
                    while(!System.Net.NetworkInformation.NetworkInterface.GetIsNetworkAvailable()) {
                        if(Snapshot().Fresh)return;
                        await Task.Delay(10000,Lifetime.Token);
                    }
                    if(Snapshot().Fresh || await CalibrateAsync())return;
                    if(attempt<2)await Task.Delay(attempt==0?30000:60000,Lifetime.Token);
                }
            } catch(OperationCanceledException){} catch { /* Keep the original reference. */ }
        });
    }
    public static void Stop()=>Lifetime.Cancel();
    public static Task<bool> CalibrateAsync()
    {
        lock(Gate) {
            if(_pending is {IsCompleted:false})return _pending;
            return _pending=Task.Run(async () => {
                Changed?.Invoke();
                try {
                    for(int attempt=0;attempt<2;attempt++) {
                        var result=await ClockNtp.FetchAsync(attempt==0,Lifetime.Token);
                        if(result is null)continue;
                        lock(Gate)_reference=result;
                        WriteCache(result);
                        return true;
                    }
                    return false;
                } catch(OperationCanceledException){return false;}
                catch {return false;}
                finally {Changed?.Invoke();}
            });
        }
    }
    private static long? BootId()
    {
        using var machine=RegistryKey.OpenBaseKey(RegistryHive.LocalMachine,RegistryView.Registry64);
        using var key=machine.OpenSubKey(@"SYSTEM\CurrentControlSet\Control\Session Manager\Memory Management\PrefetchParameters");
        return key?.GetValue("BootId") is int value?value:null;
    }
    private static Mutex CacheMutex()=>new(false,@"Local\LivingUnlock.ApplicationTime."+Sid);
    private static bool Enter(Mutex mutex)
    {
        try {return mutex.WaitOne(20);} catch(AbandonedMutexException){return true;}
    }
    private static long Read(RegistryKey key,string name)=>key.GetValue(name) is long value?value:-1;
    private static void TryImport()
    {
        try {
            using var mutex=CacheMutex();if(!Enter(mutex))return;
            try {
                using var key=Registry.CurrentUser.OpenSubKey(CachePath);
                if(key is null || Read(key,"Version")!=1 || Read(key,"BootId")!=BootId())return;
                var revision=Read(key,"Revision");
                lock(Gate)if(revision==_observedRevision)return;
                var now=Environment.TickCount64;
                var tick=Read(key,"ReferenceTick");var utc=Read(key,"UtcMs");var source=Read(key,"Source");
                if(tick<0 || tick>now || now-tick>=600000 || utc is <946684800000 or >4102444800000 || source is <0 or >3)return;
                var sample=new AppTimeSample(utc,tick,Read(key,"AgeMs"),Read(key,"UncertaintyMs"),(int)source).At(now);
                if(!sample.Fresh)return;
                lock(Gate){_reference=sample;_observedRevision=revision;}
            } finally {mutex.ReleaseMutex();}
        } catch { /* Cache access failure is not a time or authentication dependency. */ }
    }
    private static void WriteCache(AppTimeSample value)
    {
        try {
            var boot=BootId();if(boot is null)return;
            using var mutex=CacheMutex();if(!Enter(mutex))return;
            try {
                using var key=Registry.CurrentUser.CreateSubKey(CachePath);
                var sample=value.At(Environment.TickCount64);if(!sample.Fresh)return;
                var previous=Read(key,"Revision");var revision=previous is >=0 and <long.MaxValue?previous+1:1;
                void Put(string name,long data)=>key.SetValue(name,data,RegistryValueKind.QWord);
                Put("Version",1);Put("BootId",boot.Value);Put("ReferenceTick",sample.Tick);Put("UtcMs",sample.UtcMs);
                Put("AgeMs",sample.AgeMs);Put("UncertaintyMs",sample.UncertaintyMs);Put("Source",sample.Source);Put("Revision",revision);
                lock(Gate)_observedRevision=revision;
            } finally {mutex.ReleaseMutex();}
        } catch { /* A local calibrated clock remains usable without shared storage. */ }
    }
}
