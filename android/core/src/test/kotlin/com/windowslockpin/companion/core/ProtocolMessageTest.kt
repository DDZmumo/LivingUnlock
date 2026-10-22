package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolMessageTest {

    @Test
    fun testPairRequestRoundtrip() {
        val devId = DeviceId("android-pixel-7a-uuid")
        val devName = "Pixel 7a"
        val token = PairingToken(ByteArray(24) { it.toByte() })
        val clientPub = ByteArray(65) { 0x04.toByte() }
        val req = PairRequest(devId, devName, token, clientPub)

        val frame = req.toFrame(RequestId(100L))
        val decoded = ProtocolMessage.decode(frame)

        assertTrue(decoded is PairRequest)
        val decodedReq = decoded as PairRequest
        assertEquals(devId, decodedReq.deviceId)
        assertEquals(devName, decodedReq.deviceName)
        assertEquals(token, decodedReq.pairingToken)
        assertArrayEquals(clientPub, decodedReq.clientPublicKey)
    }

    @Test
    fun testPairResponseRoundtrip() {
        val serverPub = ByteArray(65) { 0x04.toByte() }
        val tag = ByteArray(32) { 0xAA.toByte() }
        val resp = PairResponse(0, serverPub, tag)

        val frame = resp.toFrame(RequestId(101L))
        val decoded = ProtocolMessage.decode(frame)

        assertTrue(decoded is PairResponse)
        val decodedResp = decoded as PairResponse
        assertEquals(0, decodedResp.statusCode)
        assertArrayEquals(serverPub, decodedResp.serverPublicKey)
        assertArrayEquals(tag, decodedResp.confirmationTag)
        assertTrue(decodedResp.isSuccess)
    }

    @Test
    fun testUnlockChallengeRoundtrip() {
        val pcId = PcId.fromString("abcdef0123456789abcdef0123456789")
        val nonce = ChallengeNonce(ByteArray(32) { (it * 3).toByte() })
        val now = 1700000000000L
        val ttl = 30000L
        val challenge = UnlockChallenge(pcId, nonce, now, ttl, "Alice")

        val frame = challenge.toFrame(RequestId(102L))
        val decoded = ProtocolMessage.decode(frame)

        assertTrue(decoded is UnlockChallenge)
        val decodedCh = decoded as UnlockChallenge
        assertEquals(pcId, decodedCh.pcId)
        assertEquals(nonce, decodedCh.challengeNonce)
        assertEquals(now, decodedCh.timestampMs)
        assertEquals(ttl, decodedCh.ttlMs)
        assertEquals("Alice", decodedCh.userDisplayName)

    }

    @Test
    fun testUnlockResponseRoundtrip() {
        val nonce = ChallengeNonce(ByteArray(32) { (it * 7).toByte() })
        val sig = ByteArray(71) { 0x30.toByte() }
        val resp = UnlockResponse(UnlockResponse.STATUS_SUCCESS, nonce, sig)

        val frame = resp.toFrame(RequestId(103L))
        val decoded = ProtocolMessage.decode(frame)

        assertTrue(decoded is UnlockResponse)
        val decodedResp = decoded as UnlockResponse
        assertEquals(UnlockResponse.STATUS_SUCCESS, decodedResp.statusCode)
        assertEquals(nonce, decodedResp.challengeNonce)
        assertArrayEquals(sig, decodedResp.authSignature)
        assertTrue(decodedResp.isSuccess)
    }

    @Test
    fun testCancelAndErrorMessageRoundtrip() {
        val cancel = CancelMessage(CancelMessage.REASON_USER_CANCELLED)
        val decodedCancel = ProtocolMessage.decode(cancel.toFrame(RequestId(104L))) as CancelMessage
        assertEquals(CancelMessage.REASON_USER_CANCELLED, decodedCancel.reasonCode)

        val error = ErrorMessage(ProtocolErrorCode.FRAME_OVERSIZE.code, "Frame too large")
        val decodedError = ProtocolMessage.decode(error.toFrame(RequestId(105L))) as ErrorMessage
        assertEquals(ProtocolErrorCode.FRAME_OVERSIZE.code, decodedError.errorCode)
        assertEquals("Frame too large", decodedError.errorMessage)
    }

    @Test
    fun testUnlockResultRoundtrip() {
        val accepted = UnlockResult(UnlockResult.STATUS_ACCEPTED, "Windows unlocked successfully")
        val frameAccepted = accepted.toFrame(RequestId(108L))
        val decodedAccepted = ProtocolMessage.decode(frameAccepted) as UnlockResult
        assertEquals(UnlockResult.STATUS_ACCEPTED, decodedAccepted.statusCode)
        assertEquals("Windows unlocked successfully", decodedAccepted.message)
        assertTrue(decodedAccepted.isAccepted)

        val rejected = UnlockResult(UnlockResult.STATUS_REJECTED, "Invalid signature")
        val frameRejected = rejected.toFrame(RequestId(109L))
        val decodedRejected = ProtocolMessage.decode(frameRejected) as UnlockResult
        assertEquals(UnlockResult.STATUS_REJECTED, decodedRejected.statusCode)
        assertEquals("Invalid signature", decodedRejected.message)
        assertFalse(decodedRejected.isAccepted)

        val error = UnlockResult(UnlockResult.STATUS_ERROR, "Credential provider internal error")
        val frameError = error.toFrame(RequestId(110L))
        val decodedError = ProtocolMessage.decode(frameError) as UnlockResult
        assertEquals(UnlockResult.STATUS_ERROR, decodedError.statusCode)
        assertEquals("Credential provider internal error", decodedError.message)
        assertFalse(decodedError.isAccepted)
    }

    @Test
    fun testRejectInvalidUnlockResult() {
        assertThrows(IllegalArgumentException::class.java) {
            UnlockResult(3, "Invalid status")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnlockResult(UnlockResult.STATUS_ACCEPTED, "A".repeat(129))
        }
    }

    @Test
    fun testRejectUnknownMessageType() {
        val rawFrame = Frame.create(
            messageType = 0x7F.toByte(),
            requestId = RequestId(1L),
            payload = ByteArray(0)
        )
        assertThrows(UnknownMessageTypeException::class.java) {
            ProtocolMessage.decode(rawFrame)
        }
    }

    @Test
    fun testRejectTrailingPayloadBytes() {
        val cancel = CancelMessage(CancelMessage.REASON_USER_CANCELLED)
        val valid = cancel.toFrame(RequestId(106L))
        val withTrailing = Frame.create(valid.header.messageType, valid.header.requestId, valid.payload + 0x55)
        assertThrows(MalformedFrameException::class.java) { ProtocolMessage.decode(withTrailing) }
    }

    @Test
    fun testRejectUtf8ByteOverflowAndFailureKeyMaterial() {
        val token = PairingToken(ByteArray(24))
        val key = ByteArray(65) { 0x04 }
        assertThrows(IllegalArgumentException::class.java) {
            PairRequest(DeviceId("phone-1"), "你".repeat(22), token, key)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairResponse(1, key, ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnlockResponse(UnlockResponse.STATUS_USER_REJECTED, ChallengeNonce(ByteArray(32)), ByteArray(70))
        }
    }

    @Test
    fun testRejectMalformedUtf8() {
        val invalidUtf8ErrorPayload = byteArrayOf(0, 1, 0, 2, 0xC3.toByte(), 0x28)
        val frame = Frame.create(ProtocolConstants.MSG_ERROR, RequestId(107L), invalidUtf8ErrorPayload)
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            ProtocolMessage.decode(frame)
        }
    }
}
