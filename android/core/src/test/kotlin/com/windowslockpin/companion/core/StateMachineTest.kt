package com.windowslockpin.companion.core

import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.statemachine.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StateMachineTest {

    private val pcId = PcId.fromString("abcdef0123456789abcdef0123456789")
    private val pcName = "Host-Office"
    private val btMac = BluetoothMacAddress.parse("11:22:33:44:55:66")
    private val token = PairingToken(ByteArray(32) { 0x11.toByte() })
    private var currentTimeMs: Long = 1700000000000L

    private lateinit var stateMachine: CompanionStateMachine

    @Before
    fun setUp() {
        stateMachine = CompanionStateMachine(
            timeProvider = { currentTimeMs }
        )
    }

    private fun createValidPairingUri(expSec: Long = (currentTimeMs / 1000L) + 120): PairingUri {
        return PairingUri(
            version = 1,
            pcId = pcId,
            pcName = pcName,
            bluetoothMac = btMac,
            pairingToken = token,
            expiryEpochSec = expSec
        )
    }

    @Test
    fun testInitialState() {
        assertTrue(stateMachine.currentState is CompanionState.Unpaired)
    }

    @Test
    fun testSuccessfulPairingFlow() {
        val uri = createValidPairingUri()
        val qrResult = stateMachine.onQrScanned(uri)
        assertTrue(qrResult.isSuccess)
        assertTrue(stateMachine.currentState is CompanionState.PairingConnecting)

        val reqId = RequestId(1234L)
        val sendResult = stateMachine.onPairRequestSent(reqId)
        assertTrue(sendResult.isSuccess)
        assertTrue(stateMachine.currentState is CompanionState.PairingRequested)

        val serverPub = ByteArray(65) { 0x04.toByte() }
        val confTag = ByteArray(32) { 0x55.toByte() }
        val pairResp = PairResponse(0, serverPub, confTag)

        val pairResult = stateMachine.onPairResponseReceived(reqId, pairResp, expectedConfirmationTag = confTag)
        assertTrue(pairResult.isSuccess)
        assertTrue(stateMachine.currentState is CompanionState.PairedIdle)

        val paired = stateMachine.getPairedPcs()
        assertEquals(1, paired.size)
        assertTrue(paired.containsKey(pcId.value))
    }

    @Test
    fun testRejectExpiredQrScan() {
        val expiredUri = createValidPairingUri(expSec = (currentTimeMs / 1000L) - 10)
        val result = stateMachine.onQrScanned(expiredUri)
        assertTrue(result.isFailure)
        val fail = result as ProtocolResult.Failure
        assertEquals(ProtocolErrorCode.EXPIRED, fail.errorCode)
        assertTrue(stateMachine.currentState is CompanionState.Unpaired)
    }

    @Test
    fun testSuccessfulUnlockFlow() {
        // Setup paired state first
        testSuccessfulPairingFlow()

        val reqId = RequestId(9999L)
        val nonce = ChallengeNonce(ByteArray(32) { 0x77.toByte() })
        val challenge = UnlockChallenge(
            pcId = pcId,
            challengeNonce = nonce,
            timestampMs = currentTimeMs,
            ttlMs = 30000L,
            userDisplayName = "Alice"
        )

        val chResult = stateMachine.onUnlockChallengeReceived(reqId, challenge)
        assertTrue(chResult.isSuccess)
        assertTrue(stateMachine.currentState is CompanionState.ChallengeReceived)

        val promptResult = stateMachine.onBiometricPromptStarted()
        assertTrue(promptResult.isSuccess)
        assertTrue(stateMachine.currentState is CompanionState.BiometricPrompting)

        val completedResult = stateMachine.onBiometricPromptCompleted(success = true)
        assertTrue(completedResult.isSuccess)
        assertTrue(stateMachine.currentState is CompanionState.UnlockResponded)

        stateMachine.onUnlockCycleFinished()
        assertTrue(stateMachine.currentState is CompanionState.PairedIdle)
    }

    @Test
    fun testReplayChallengeDetection() {
        testSuccessfulPairingFlow()

        val reqId1 = RequestId(101L)
        val nonce = ChallengeNonce(ByteArray(32) { 0x88.toByte() })
        val challenge = UnlockChallenge(pcId, nonce, currentTimeMs, 30000L, "Alice")

        val r1 = stateMachine.onUnlockChallengeReceived(reqId1, challenge)
        assertTrue(r1.isSuccess)

        stateMachine.onCancelled("Simulate first challenge finished")

        // Send duplicate challenge with the exact same nonce
        val reqId2 = RequestId(102L)
        val r2 = stateMachine.onUnlockChallengeReceived(reqId2, challenge)
        assertTrue(r2.isFailure)
        val fail = r2 as ProtocolResult.Failure
        assertEquals(ProtocolErrorCode.REPLAY_DETECTED, fail.errorCode)
    }

    @Test
    fun testChallengeReceptionDoesNotDependOnHostClock() {
        testSuccessfulPairingFlow()
        for (hostOffsetMs in listOf(-29_400L, -86_400_000L, 86_400_000L)) {
            val challenge = UnlockChallenge(pcId, ChallengeNonce.generate(32),
                currentTimeMs + hostOffsetMs, 30_000L, "Alice")
            assertTrue(stateMachine.onUnlockChallengeReceived(RequestId.generate(), challenge, pcId).isSuccess)
            stateMachine.onCancelled("Finished clock-skew test request")
        }
    }

    @Test
    fun testDeviceMismatchChallengeDetection() {
        testSuccessfulPairingFlow()

        val unknownPcId = PcId.fromString("99999999999999999999999999999999")
        val nonce = ChallengeNonce(ByteArray(32) { 0xAA.toByte() })
        val challenge = UnlockChallenge(unknownPcId, nonce, currentTimeMs, 30000L, "Alice")

        val r = stateMachine.onUnlockChallengeReceived(RequestId(301L), challenge)
        assertTrue(r.isFailure)
        val fail = r as ProtocolResult.Failure
        assertEquals(ProtocolErrorCode.DEVICE_MISMATCH, fail.errorCode)
    }

    @Test
    fun testCancellationResetsToIdle() {
        testSuccessfulPairingFlow()

        val challenge = UnlockChallenge(pcId, ChallengeNonce(ByteArray(32)), currentTimeMs, 30000L, "Alice")
        stateMachine.onUnlockChallengeReceived(RequestId(401L), challenge)
        stateMachine.onBiometricPromptStarted()
        assertTrue(stateMachine.currentState is CompanionState.BiometricPrompting)

        stateMachine.onCancelled("User dismissed prompt")
        assertTrue(stateMachine.currentState is CompanionState.PairedIdle)
    }

    @Test
    fun testFullUnlockWithUnlockResultAccepted() {
        testSuccessfulPairingFlow()

        val reqId = RequestId(501L)
        val nonce = ChallengeNonce(ByteArray(32) { 0x55.toByte() })
        val challenge = UnlockChallenge(pcId, nonce, currentTimeMs, 30000L, "Alice")

        val chResult = stateMachine.onUnlockChallengeReceived(reqId, challenge, expectedPcId = pcId)
        assertTrue(chResult.isSuccess)
        assertTrue(stateMachine.currentState is CompanionState.ChallengeReceived)

        assertTrue(stateMachine.onBiometricPromptStarted().isSuccess)
        assertTrue(stateMachine.currentState is CompanionState.BiometricPrompting)

        assertTrue(stateMachine.onBiometricPromptCompleted(success = true).isSuccess)
        assertTrue(stateMachine.currentState is CompanionState.UnlockResponded)

        assertTrue(stateMachine.onUnlockResponseSent(reqId).isSuccess)
        assertTrue(stateMachine.currentState is CompanionState.AwaitingUnlockResult)

        val unlockResult = UnlockResult(UnlockResult.STATUS_ACCEPTED, "Unlocked")
        val res = stateMachine.onUnlockResultReceived(reqId, unlockResult)
        assertTrue(res.isSuccess)
        assertTrue(stateMachine.currentState is CompanionState.UnlockCompleted)

        stateMachine.onUnlockCycleFinished()
        assertTrue(stateMachine.currentState is CompanionState.PairedIdle)
    }

    @Test
    fun testRejectSecondChallengeWhenRequestInProgress() {
        testSuccessfulPairingFlow()

        val reqId1 = RequestId(601L)
        val nonce1 = ChallengeNonce(ByteArray(32) { 0x61.toByte() })
        val ch1 = UnlockChallenge(pcId, nonce1, currentTimeMs, 30000L, "Alice")
        assertTrue(stateMachine.onUnlockChallengeReceived(reqId1, ch1).isSuccess)

        // Machine is in ChallengeReceived; second challenge must be rejected
        val reqId2 = RequestId(602L)
        val nonce2 = ChallengeNonce(ByteArray(32) { 0x62.toByte() })
        val ch2 = UnlockChallenge(pcId, nonce2, currentTimeMs, 30000L, "Alice")
        val r2 = stateMachine.onUnlockChallengeReceived(reqId2, ch2)
        assertTrue(r2.isFailure)
        val fail = r2 as ProtocolResult.Failure
        assertEquals(ProtocolErrorCode.UNEXPECTED_STATE, fail.errorCode)
    }

    @Test
    fun testRejectChallengeWhenConnectedPcMismatch() {
        testSuccessfulPairingFlow()

        val otherConnectedPcId = PcId.fromString("88888888888888888888888888888888")
        val reqId = RequestId(701L)
        val nonce = ChallengeNonce(ByteArray(32) { 0x71.toByte() })
        val ch = UnlockChallenge(pcId, nonce, currentTimeMs, 30000L, "Alice")

        // Expected connected PC is different from challenge PC
        val result = stateMachine.onUnlockChallengeReceived(reqId, ch, expectedPcId = otherConnectedPcId)
        assertTrue(result.isFailure)
        val fail = result as ProtocolResult.Failure
        assertEquals(ProtocolErrorCode.DEVICE_MISMATCH, fail.errorCode)
    }

    @Test
    fun testUnlockResultRequestIdMismatch() {
        testSuccessfulPairingFlow()

        val reqId = RequestId(801L)
        val nonce = ChallengeNonce(ByteArray(32) { 0x81.toByte() })
        val ch = UnlockChallenge(pcId, nonce, currentTimeMs, 30000L, "Alice")
        assertTrue(stateMachine.onUnlockChallengeReceived(reqId, ch).isSuccess)
        assertTrue(stateMachine.onBiometricPromptStarted().isSuccess)
        assertTrue(stateMachine.onBiometricPromptCompleted(true).isSuccess)
        assertTrue(stateMachine.onUnlockResponseSent(reqId).isSuccess)

        val wrongReqId = RequestId(999L)
        val wrongResult = stateMachine.onUnlockResultReceived(wrongReqId, UnlockResult(0, "OK"))
        assertTrue(wrongResult.isFailure)
        val fail = wrongResult as ProtocolResult.Failure
        assertEquals(ProtocolErrorCode.DEVICE_MISMATCH, fail.errorCode)
    }
}
