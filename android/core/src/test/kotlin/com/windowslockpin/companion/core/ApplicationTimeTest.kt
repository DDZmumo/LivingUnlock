package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.time.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ApplicationTimeTest {
    private val epoch=1700000000000L
    @Test fun monotonicClockAndSampleAgeArePreserved() {
        var tick=100L
        val clock=ApplicationTime(epoch){tick}
        tick+=500;assertEquals(epoch+500,clock.snapshot().utcMs)
        assertTrue(clock.apply(TimeSample(epoch+30000,100,10,0)))
        tick+=1000
        assertEquals(epoch+31000,clock.snapshot().utcMs)
        assertEquals(1100,clock.snapshot().ageMs)
        tick+=599000
        assertFalse(clock.snapshot().isFresh)
        assertEquals(epoch+630000,clock.snapshot().utcMs)
        assertFalse(clock.apply(TimeSample(epoch,TIME_CACHE_MS,10,0)))
    }
    @Test fun signedWireMatchesWindowsAndRejectsTampering() {
        val key=ByteArray(32){42}
        val packet=TimePacket(TimeKind.PROBE,0x0102030405060708,ByteArray(32),TimeSample(epoch))
        val encoded=TimeWire.encode(packet,key)
        val golden="4c55544d0100ff00010203040506070800000000000000000000000000000000000000000000000000000000000000000000018bcfe56800000000000000000000000000da15aa68c04a09e239b484f450e98542a47ecf91a3732f579388ded0cea77560"
        assertEquals(golden,encoded.joinToString(""){"%02x".format(it)})
        assertEquals(packet.requestId,TimeWire.decode(encoded,key)!!.requestId)
        encoded.indices.forEach { index ->
            val corrupt=encoded.copyOf();corrupt[index]=(corrupt[index].toInt() xor 1).toByte()
            assertNull(TimeWire.decode(corrupt,key))
        }
        assertNull(TimeWire.decode(encoded,ByteArray(32){43}))
    }
    @Test fun ntpChecksIdentityStatusAndUsesMonotonicRoundTrip() {
        val nonce=ByteArray(8){(it+1).toByte()}
        val bytes=ByteArray(48).apply { this[0]=0x24;this[1]=2;nonce.copyInto(this,24) }
        val b=ByteBuffer.wrap(bytes)
        listOf(32,40).forEach { offset -> b.putInt(offset,(epoch/1000+2208988800L).toInt());b.putInt(offset+4,0) }
        assertEquals(epoch+50,NtpTime.decode(bytes,nonce,100,0)!!.utcMs)
        bytes[24]=0;assertNull(NtpTime.decode(bytes,nonce,100,0));bytes[24]=1
        bytes[1]=0;assertNull(NtpTime.decode(bytes,nonce,100,0));bytes[1]=2
        bytes[0]=0xe4.toByte();assertNull(NtpTime.decode(bytes,nonce,100,0))
    }
    @Test fun parallelSourcesPreferNtscAndRejectOutliersAndConflict() {
        fun sample(offset: Long,source: Int)=TimedSample(TimeSample(epoch+offset,uncertaintyMs=10,source=source),0)
        assertEquals(0,NtpTime.select(listOf(sample(0,0),sample(20,1),sample(10,2)),0)!!.source)
        assertEquals(1,NtpTime.select(listOf(sample(10000,0),sample(20,1),sample(10,2)),0)!!.source)
        assertNull(NtpTime.select(listOf(sample(0,0),sample(10000,1)),0))
    }
    @Test fun manualOfflineCalibrationDoesNotClaimCachedSuccessOrDiscardReference() {
        val clock=ApplicationTime(epoch){100L}
        assertTrue(clock.apply(TimeSample(epoch+29400,10,10,0)))
        val client=NetworkTimeClient({100L})
        assertFalse(client.refresh(clock,online=false,forceNewSample=true))
        assertEquals(epoch+29400,clock.snapshot().utcMs)
        assertTrue(clock.snapshot().isFresh)
    }
}
