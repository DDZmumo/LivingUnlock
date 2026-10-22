using System.Buffers.Binary;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace LivingUnlock.Windows.Services;

public static class ClockNtp
{
    public static readonly string[] Servers={"ntp.ntsc.ac.cn","ntp.aliyun.com","ntp.tencent.com","time.windows.com"};
    public static AppTimeSample? Decode(byte[] bytes,byte[] nonce,long rtt,int source,long tick)
    {
        if(bytes.Length<48 || nonce.Length!=8 || rtt is <0 or >1500 || source is <0 or >3)return null;
        int flags=bytes[0];
        if(flags>>6==3 || (flags&7)!=4 || ((flags>>3)&7) is <3 or >4 || bytes[1] is <1 or >15 ||
            !bytes.AsSpan(24,8).SequenceEqual(nonce))return null;
        long Word(int offset)=>BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(offset,4));
        long Stamp(int offset){long seconds=Word(offset);if(seconds<2208988800L)seconds+=0x100000000L;
            return (seconds-2208988800L)*1000+(Word(offset+4)*1000>>32);}
        long received=Stamp(32),sent=Stamp(40),processing=sent-received;
        if(sent is <946684800000 or >4102444800000 || processing is <0 or >1000 || processing>rtt+5 || (bytes[4]&128)!=0)return null;
        long transit=(Math.Max(0,rtt-processing)+1)/2;
        long uncertainty=Math.Max(1,transit+Word(8)*1000/65536+Word(4)*1000/65536/2+1);
        var sample=new AppTimeSample(sent+transit,tick,0,uncertainty,source);
        return sample.Fresh?sample:null;
    }
    public static AppTimeSample? Select(IEnumerable<AppTimeSample> values,long now)
    {
        var samples=values.Select(x=>x.At(now)).Where(x=>x.Fresh).ToArray();
        return samples.Where(a=>samples.Length==1 || samples.Any(b=>!ReferenceEquals(a,b) &&
            Math.Abs(a.UtcMs-b.UtcMs)<=Math.Max(1000,a.UncertaintyMs+b.UncertaintyMs))).OrderBy(x=>x.Source).FirstOrDefault();
    }
    public static async Task<AppTimeSample?> FetchAsync(bool preferDirect,CancellationToken cancellationToken)
    {
        var results=await Task.WhenAll(Enumerable.Range(0,3).Select(i=>QueryAsync(i,preferDirect,cancellationToken))).ConfigureAwait(false);
        var selected=Select(results.OfType<AppTimeSample>(),Environment.TickCount64);
        return selected ?? await QueryAsync(3,preferDirect,cancellationToken).ConfigureAwait(false);
    }
    private static async Task<AppTimeSample?> QueryAsync(int index,bool direct,CancellationToken cancellationToken)
    {
        using var timeout=CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);timeout.CancelAfter(1200);
        try {
            var addresses=await Dns.GetHostAddressesAsync(Servers[index]).WaitAsync(timeout.Token).ConfigureAwait(false);
            var address=addresses.FirstOrDefault(x=>x.AddressFamily==AddressFamily.InterNetwork);if(address is null)return null;
            using var udp=new UdpClient(AddressFamily.InterNetwork);
            var ip=address.GetAddressBytes();bool fakeIp=ip[0]==198 && ip[1] is 18 or 19;
            if(direct && !fakeIp) {
                try {
                    var adapter=NetworkInterface.GetAllNetworkInterfaces().FirstOrDefault(x=>x.OperationalStatus==OperationalStatus.Up &&
                        x.NetworkInterfaceType is NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211 &&
                        x.GetIPProperties().GatewayAddresses.Any(g=>g.Address.AddressFamily==AddressFamily.InterNetwork));
                    // Winsock IP_UNICAST_IF=31 is not named by .NET 8's enum.
                    if(adapter is not null)udp.Client.SetSocketOption(SocketOptionLevel.IP,(SocketOptionName)31,
                        IPAddress.HostToNetworkOrder(adapter.GetIPProperties().GetIPv4Properties().Index));
                } catch { /* Let the system route decide if direct binding is unavailable. */ }
            }
            udp.Connect(address,123);
            var nonce=RandomNumberGenerator.GetBytes(8);var request=new byte[48];request[0]=0x23;nonce.CopyTo(request,40);
            long start=Environment.TickCount64;
            await udp.SendAsync(request,request.Length).ConfigureAwait(false);
            var reply=await udp.ReceiveAsync(timeout.Token).ConfigureAwait(false);long end=Environment.TickCount64;
            return Decode(reply.Buffer,nonce,end-start,index,end);
        } catch {return null;}
    }
}
