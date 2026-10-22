package com.windowslockpin.companion.time

import android.content.Context
import android.os.SystemClock
import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import com.windowslockpin.companion.core.time.*
import com.windowslockpin.companion.core.transport.TransportConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class TimeHandshake(private val record: PairedPcRecord,private val connection: TransportConnection) {
    private var probe: TimePacket?=null
    private var stage=0
    private var startedTick=0L
    private var probeCount=0
    private var calibrationCount=0
    private var commitCount=0
    val isPreparing get()=stage in 1..3
    private val snapshot get()=AppTime.clock.snapshot()
    suspend fun handle(context: Context,frame: Frame): Boolean {
        if(!TimeWire.isTimePacket(frame.payload))return false
        try {
            val packet=TimeWire.decode(frame.payload,record.kPair) ?: return true
            if(packet.requestId!=frame.header.requestId.value)return true
            suspend fun reply(kind: TimeKind,durationMs: Long=0) {
                val response=TimePacket(kind,packet.requestId,packet.nonce,snapshot,durationMs)
                connection.sendFrame(Frame.create(ProtocolConstants.MSG_PING,frame.header.requestId,TimeWire.encode(response,record.kPair)))
            }
            when(packet.kind) {
                TimeKind.PROBE -> {
                    if(probeCount>=2)return true
                    if(stage==0) {
                        if(!rememberProbe(record.pcId.value,packet))return true
                        probe=packet;stage=1;startedTick=SystemClock.elapsedRealtime()
                    } else if(stage!=1 || !matches(packet))return true
                    probeCount++
                    AppTime.show(packet.requestId,record.pcName,"正在与电脑核对内部时间")
                    AppTime.setCancellation {
                        try { connection.sendFrame(CancelMessage(CancelMessage.REASON_USER_CANCELLED).toFrame(frame.header.requestId)) }
                        finally { connection.close() }
                    }
                    reply(TimeKind.PROBE_REPLY)
                }
                TimeKind.CALIBRATE -> {
                    if(!matches(packet) || stage !in 1..2 || calibrationCount>=2)return true
                    stage=2
                    calibrationCount++
                    AppTime.show(packet.requestId,record.pcName,"时间偏差较大，手机正在尝试取时")
                    val started=SystemClock.elapsedRealtime()
                    withContext(Dispatchers.IO) {
                        repeat(if(calibrationCount==1)2 else 0) { attempt ->
                            if(AppTime.network.refresh(AppTime.clock,AppTime.online(context),oneRetry=attempt==1))return@withContext
                        }
                    }
                    reply(TimeKind.SAMPLE_REPLY,(SystemClock.elapsedRealtime()-started).coerceAtMost(4_000))
                    if(!snapshot.isFresh)AppTime.show(packet.requestId,record.pcName,"手机暂未取得时间，等待电脑校准")
                }
                TimeKind.COMMIT -> {
                    if(!matches(packet) || stage !in 1..3 || commitCount>=2)return true
                    commitCount++
                    if(AppTime.clock.apply(packet.sample,packet.durationMs)) {
                        SafeLogger.i("AppTime","Authenticated peer calibration applied; source=${packet.sample.source}")
                        AppTime.show(packet.requestId,record.pcName,"内部时间已同步，等待解锁请求")
                    } else AppTime.show(packet.requestId,record.pcName,"使用独立计时，等待解锁请求")
                    stage=3
                    reply(TimeKind.COMMIT_REPLY)
                }
                else -> {}
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            probe?.let { AppTime.show(it.requestId,record.pcName,"时间同步未完成，使用独立计时继续") }
        }
        return true
    }
    private fun matches(packet: TimePacket): Boolean {
        val previous=probe ?: return false
        return packet.requestId==previous.requestId && packet.nonce.contentEquals(previous.nonce) &&
            SystemClock.elapsedRealtime()-startedTick<30_000
    }
    fun close(){probe?.let { AppTime.clear(it.requestId) }}
    companion object {
        private val seen=LinkedHashSet<String>()
        @Synchronized private fun rememberProbe(pcId: String,packet: TimePacket): Boolean {
            val identity=pcId+":"+packet.nonce.joinToString(""){"%02x".format(it)}
            if(!seen.add(identity))return false
            while(seen.size>128)seen.remove(seen.first())
            return true
        }
    }
}
