package com.windowslockpin.companion.core.time

import com.windowslockpin.companion.core.crypto.CryptoEngine
import java.nio.ByteBuffer

const val TIME_CACHE_MS = 600_000L
const val TIME_DIFFERENCE_MS = 2_000L
const val SYSTEM_TIME_SOURCE = 255

data class TimeSample(val utcMs: Long, val ageMs: Long = 0, val uncertaintyMs: Long = 0,
                      val source: Int = SYSTEM_TIME_SOURCE) {
    val isFresh: Boolean get() = source in 0..3 && ageMs in 0 until TIME_CACHE_MS && uncertaintyMs in 0..2_000
}

class ApplicationTime(initialUtcMs: Long, private val monotonicMs: () -> Long) {
    private var sample = TimeSample(initialUtcMs)
    private var referenceTick = monotonicMs()

    @Synchronized fun snapshot(): TimeSample {
        val elapsed = (monotonicMs() - referenceTick).coerceAtLeast(0)
        val age = sample.ageMs + elapsed
        return if (sample.source in 0..3 && age < TIME_CACHE_MS) {
            sample.copy(utcMs = sample.utcMs + elapsed, ageMs = age)
        } else TimeSample(sample.utcMs + elapsed)
    }

    @Synchronized fun apply(value: TimeSample, transitMs: Long = 0): Boolean {
        if (!value.isFresh || transitMs !in 0..1_000 || value.ageMs + transitMs >= TIME_CACHE_MS ||
            value.uncertaintyMs + transitMs > 2_000) return false
        sample = value.copy(utcMs = value.utcMs + transitMs, ageMs = value.ageMs + transitMs,
            uncertaintyMs = value.uncertaintyMs + transitMs)
        referenceTick = monotonicMs()
        return true
    }
}

enum class TimeKind { PROBE, PROBE_REPLY, CALIBRATE, SAMPLE_REPLY, COMMIT, COMMIT_REPLY }
data class TimePacket(val kind: TimeKind, val requestId: Long, val nonce: ByteArray,
                      val sample: TimeSample, val durationMs: Long = 0)

object TimeWire {
    private val magic = "LUTM".toByteArray(Charsets.US_ASCII)
    private val domain = "LivingUnlock application time v1".toByteArray(Charsets.US_ASCII)
    fun isTimePacket(bytes: ByteArray) = bytes.size >= 4 && bytes.copyOfRange(0,4).contentEquals(magic)
    private fun mac(key: ByteArray, body: ByteArray): ByteArray {
        val derived = CryptoEngine.hmacSha256(key, domain)
        return try { CryptoEngine.hmacSha256(derived, body) } finally { derived.fill(0) }
    }
    fun encode(packet: TimePacket, key: ByteArray): ByteArray {
        require(key.size == 32 && packet.nonce.size == 32)
        val buffer = ByteBuffer.allocate(68).put(magic).put(1).put(packet.kind.ordinal.toByte())
            .put(packet.sample.source.toByte()).put(0).putLong(packet.requestId).put(packet.nonce)
            .putLong(packet.sample.utcMs).putInt(packet.sample.ageMs.toInt())
            .putInt(packet.sample.uncertaintyMs.toInt()).putInt(packet.durationMs.toInt())
        val body = buffer.array()
        return body + mac(key, body)
    }
    fun decode(bytes: ByteArray, key: ByteArray): TimePacket? {
        if (bytes.size != 100 || key.size != 32 || !isTimePacket(bytes) || bytes[4] != 1.toByte() ||
            bytes[5].toInt() !in 0..5 || bytes[7] != 0.toByte()) return null
        if (!CryptoEngine.constantTimeEquals(mac(key, bytes.copyOfRange(0,68)), bytes.copyOfRange(68,100))) return null
        val b = ByteBuffer.wrap(bytes).apply { position(8) }
        val id = b.long
        val nonce = ByteArray(32).also { b.get(it) }
        val sample = TimeSample(b.long, b.int.toLong() and 0xffffffffL,
            b.int.toLong() and 0xffffffffL, bytes[6].toInt() and 255)
        val duration = b.int.toLong() and 0xffffffffL
        if (sample.utcMs !in 946684800000L..4102444800000L || duration > 4_000) return null
        if (sample.source == SYSTEM_TIME_SOURCE) {
            if (sample.ageMs != 0L || sample.uncertaintyMs != 0L) return null
        } else if (!sample.isFresh) return null
        return TimePacket(TimeKind.entries[bytes[5].toInt()], id, nonce, sample, duration)
    }
}
