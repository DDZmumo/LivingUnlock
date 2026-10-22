package com.windowslockpin.companion.core.time

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class TimedSample(val sample: TimeSample, val receivedTick: Long)
data class DatagramRoute(val address: InetAddress, val bind: (DatagramSocket) -> Unit = {})

object NtpTime {
    val servers = listOf("ntp.ntsc.ac.cn", "ntp.aliyun.com", "ntp.tencent.com", "time.windows.com")
    fun decode(reply: ByteArray, nonce: ByteArray, roundTripMs: Long, source: Int): TimeSample? {
        if (reply.size < 48 || nonce.size != 8 || roundTripMs !in 0..1_500 || source !in 0..3) return null
        val flags = reply[0].toInt() and 255
        if (flags ushr 6 == 3 || (flags and 7) != 4 || ((flags ushr 3) and 7) !in 3..4 ||
            (reply[1].toInt() and 255) !in 1..15 || !reply.copyOfRange(24,32).contentEquals(nonce)) return null
        val b = ByteBuffer.wrap(reply)
        fun uint(offset: Int) = b.getInt(offset).toLong() and 0xffffffffL
        fun timestamp(offset: Int): Long {
            var seconds = uint(offset)
            if (seconds < 2208988800L) seconds += 0x100000000L // NTP era rollover in 2036
            return (seconds - 2208988800L)*1000 + (uint(offset+4)*1000 ushr 32)
        }
        val received = timestamp(32)
        val sent = timestamp(40)
        val processing = sent - received
        if (sent !in 946684800000L..4102444800000L || processing !in 0..1_000 || processing > roundTripMs+5) return null
        val delay = b.getInt(4).toLong()*1000/65536
        if (delay < 0) return null
        val transit = ((roundTripMs-processing).coerceAtLeast(0)+1)/2
        val uncertainty = maxOf(1, transit + uint(8)*1000/65536 + delay/2 + 1)
        return TimeSample(sent+transit, uncertaintyMs=uncertainty, source=source).takeIf { it.isFresh }
    }

    // Compare samples at a common monotonic instant. Reject conflicting pairs
    // and isolated outliers, then prefer NTSC over the other valid sources.
    fun select(samples: List<TimedSample>, now: Long): TimeSample? {
        val current = samples.map { val elapsed=(now-it.receivedTick).coerceAtLeast(0)
            it.sample.copy(utcMs=it.sample.utcMs+elapsed,ageMs=it.sample.ageMs+elapsed) }
            .filter { it.isFresh }
        fun compatible(a: TimeSample, b: TimeSample) = abs(a.utcMs-b.utcMs) <= maxOf(1000, a.uncertaintyMs+b.uncertaintyMs)
        val agreeing = current.filter { a -> current.size == 1 || current.any { b -> a !== b && compatible(a,b) } }
        return agreeing.minByOrNull { it.source }
    }
}

class NetworkTimeClient(private val monotonicMs: () -> Long,
    private val route: (String,Boolean) -> DatagramRoute? = { host,_ -> DatagramRoute(InetAddress.getByName(host)) }) {
    private val workers = ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,ArrayBlockingQueue(4),
        { task -> Thread(task,"LivingUnlock-NTP").apply { isDaemon=true } }, ThreadPoolExecutor.AbortPolicy())
    private var nextAttempt = Long.MIN_VALUE
    private var failures = 0

    @Synchronized fun refresh(clock: ApplicationTime, online: Boolean, oneRetry: Boolean=false, forceNewSample: Boolean=false): Boolean {
        if (clock.snapshot().isFresh && !forceNewSample) return true
        val now=monotonicMs()
        if (!online || (now < nextAttempt && !oneRetry && !forceNewSample)) return false
        nextAttempt=now+30_000
        val deadline=now+1_200
        val preferDirect=failures==0
        val futures=(0..2).map { index ->
            try { workers.submit<TimedSample?> { query(index,preferDirect) } } catch (_: Exception) { null }
        }
        val samples=futures.mapNotNull { future ->
            try { future?.get((deadline-monotonicMs()).coerceAtLeast(1),TimeUnit.MILLISECONDS) }
            catch (_: Exception) { null } finally { future?.cancel(true) }
        }
        var selected=NtpTime.select(samples,monotonicMs())
        if (selected==null) {
            val fallback=try { workers.submit<TimedSample?> { query(3,preferDirect) } } catch (_: Exception) { null }
            val value=try { fallback?.get(1_200,TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
                finally { fallback?.cancel(true) }
            selected=NtpTime.select(listOfNotNull(value),monotonicMs())
        }
        if (selected!=null && clock.apply(selected)) {
            failures=0;nextAttempt=monotonicMs()+TIME_CACHE_MS;return true
        }
        failures=(failures+1).coerceAtMost(5)
        nextAttempt=monotonicMs()+minOf(300_000L,30_000L shl (failures-1))
        return false
    }

    private fun query(index: Int,preferDirect: Boolean): TimedSample? = try {
        val selected=route(NtpTime.servers[index],preferDirect)
        if (selected==null || Thread.currentThread().isInterrupted) null else DatagramSocket().use { socket ->
            selected.bind(socket)
            socket.connect(selected.address,123);socket.soTimeout=650
            val request=ByteArray(48).apply { this[0]=0x23 }
            val nonce=ByteArray(8).also { SecureRandom().nextBytes(it) }
            nonce.copyInto(request,40)
            val start=monotonicMs()
            socket.send(DatagramPacket(request,request.size))
            val reply=DatagramPacket(ByteArray(512),512)
            socket.receive(reply)
            val end=monotonicMs()
            NtpTime.decode(reply.data.copyOf(reply.length),nonce,end-start,index)?.let { TimedSample(it,end) }
        }
    } catch (_: Exception) { null }
}
