package com.windowslockpin.companion.time

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import com.windowslockpin.companion.core.time.*
import com.windowslockpin.companion.core.transport.TransportConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class TimeHandshakeTest {
    private class RecordingConnection: TransportConnection {
        val sent=ArrayDeque<Frame>()
        override var isConnected=true
        override suspend fun sendFrame(frame: Frame){sent.addLast(frame)}
        override suspend fun receiveFrame(): Frame=error("Handshake uses the service's frame reader")
        override fun close(){isConnected=false}
    }
    private val epoch=1700000000000L
    private val key=ByteArray(32){42}
    private val record=PairedPcRecord(PcId.fromString("abcdef0123456789abcdef0123456789"),"Test PC",
        BluetoothMacAddress.parse("11:22:33:44:55:66"),ByteArray(65){4},key,epoch)
    @Before fun reset(){AppTime.resetForTests(epoch)}
    private fun packet(kind: TimeKind,nonce: ByteArray,sample: TimeSample=TimeSample(epoch),id: Long=99)=
        TimePacket(kind,id,nonce,sample)
    private fun frame(packet: TimePacket)=Frame.create(ProtocolConstants.MSG_PONG,RequestId(packet.requestId),TimeWire.encode(packet,key))

    @Test fun preparationIsSeparateAndCommitIsAuthenticatedAndIdempotent()=runBlocking {
        val context: Context=RuntimeEnvironment.getApplication()
        val phone=RecordingConnection()
        val handler=TimeHandshake(record,phone)
        val nonce=ByteArray(32).also { SecureRandom().nextBytes(it) }
        handler.handle(context,frame(packet(TimeKind.PROBE,nonce)))
        assertNotNull(AppTime.preparation.value)
        assertEquals(TimeKind.PROBE_REPLY,TimeWire.decode(phone.sent.removeFirst().payload,key)!!.kind)
        handler.handle(context,frame(packet(TimeKind.PROBE,nonce)))
        assertEquals(TimeKind.PROBE_REPLY,TimeWire.decode(phone.sent.removeFirst().payload,key)!!.kind)
        val commit=packet(TimeKind.COMMIT,nonce,TimeSample(epoch+29400,100,10,0))
        val wrong=commit.copy(nonce=ByteArray(32){7})
        handler.handle(context,frame(wrong))
        assertEquals(epoch,AppTime.clock.snapshot().utcMs)
        handler.handle(context,frame(commit))
        assertEquals(TimeKind.COMMIT_REPLY,TimeWire.decode(phone.sent.removeFirst().payload,key)!!.kind)
        assertEquals(epoch+29400,AppTime.clock.snapshot().utcMs)
        assertEquals(100,AppTime.clock.snapshot().ageMs)
        handler.handle(context,frame(commit))
        assertEquals(TimeKind.COMMIT_REPLY,TimeWire.decode(phone.sent.removeFirst().payload,key)!!.kind)
        handler.handle(context,frame(commit))
        assertTrue(phone.sent.isEmpty())
        handler.close();assertNull(AppTime.preparation.value)
        phone.close()
    }
    @Test fun noNetworkContinuesWithIndependentClockAndBoundedReplies()=runBlocking {
        val context: Context=RuntimeEnvironment.getApplication()
        val phone=RecordingConnection()
        val handler=TimeHandshake(record,phone)
        val nonce=ByteArray(32).also { SecureRandom().nextBytes(it) }
        handler.handle(context,frame(packet(TimeKind.PROBE,nonce)))
        phone.sent.removeFirst()
        repeat(2) {
            handler.handle(context,frame(packet(TimeKind.CALIBRATE,nonce)))
            assertFalse(TimeWire.decode(phone.sent.removeFirst().payload,key)!!.sample.isFresh)
        }
        handler.handle(context,frame(packet(TimeKind.CALIBRATE,nonce)))
        assertTrue(phone.sent.isEmpty())
        handler.handle(context,frame(packet(TimeKind.COMMIT,nonce)))
        assertEquals(TimeKind.COMMIT_REPLY,TimeWire.decode(phone.sent.removeFirst().payload,key)!!.kind)
        assertFalse(AppTime.clock.snapshot().isFresh)
        handler.close();phone.close()
    }
    @Test fun badMacCannotOpenPreparationOrChangeTime()=runBlocking {
        val context: Context=RuntimeEnvironment.getApplication()
        val phone=RecordingConnection()
        val handler=TimeHandshake(record,phone)
        val probe=packet(TimeKind.PROBE,ByteArray(32){5})
        val invalid=TimeWire.encode(probe,key).apply { this[80]=(this[80].toInt() xor 1).toByte() }
        handler.handle(context,Frame.create(ProtocolConstants.MSG_PONG,RequestId(99),invalid))
        assertNull(AppTime.preparation.value)
        assertTrue(phone.sent.isEmpty())
        assertEquals(epoch,AppTime.clock.snapshot().utcMs)
        phone.close()
    }
}
