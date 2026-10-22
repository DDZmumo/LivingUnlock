using LivingUnlock.Windows.Services;
using System.Buffers.Binary;

static void Require(bool value,string message){if(!value)throw new Exception(message);}
const long epoch=1700000000000;
var sample=new AppTimeSample(epoch,100,100,10,0).At(1100);
Require(sample.UtcMs==epoch+1000 && sample.AgeMs==1100,"Shared samples preserve their original age");
Require(!sample.At(600100).Fresh,"An old sample must not be advertised as freshly calibrated");
var nonce=new byte[]{1,2,3,4,5,6,7,8};var packet=new byte[48];packet[0]=0x24;packet[1]=2;nonce.CopyTo(packet,24);
foreach(int offset in new[]{32,40})BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(offset,4),(uint)(epoch/1000+2208988800L));
Require(ClockNtp.Decode(packet,nonce,100,0,0)?.UtcMs==epoch+50,"NTP decode uses monotonic round-trip time");
packet[24]^=1;Require(ClockNtp.Decode(packet,nonce,100,0,0) is null,"Reject unrelated NTP response");packet[24]^=1;
packet[1]=0;Require(ClockNtp.Decode(packet,nonce,100,0,0) is null,"Reject Kiss-of-Death response");
AppTimeSample Value(long delta,int source)=>new(epoch+delta,0,0,10,source);
Require(ClockNtp.Select(new[]{Value(0,0),Value(20,1),Value(10,2)},0)?.Source==0,"Prefer consistent NTSC sample");
Require(ClockNtp.Select(new[]{Value(10000,0),Value(20,1),Value(10,2)},0)?.Source==1,"Reject NTSC outlier");
Require(ClockNtp.Select(new[]{Value(0,0),Value(10000,1)},0) is null,"Reject conflicting pair");
Console.WriteLine("PASS: application-time projection, cache age, NTP identity and source selection");
if(args.Contains("--live")) {
    bool success=await ApplicationTimeService.CalibrateAsync();
    var current=ApplicationTimeService.Snapshot();
    Console.WriteLine($"LIVE_NTP success={success} source={current.Source} utc={current.UtcMs} ageMs={current.AgeMs}");
    ApplicationTimeService.Stop();
}
