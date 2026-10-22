package com.windowslockpin.companion.service

import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.statemachine.CompanionStateMachine
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import com.windowslockpin.companion.core.storage.DeviceIdProvider
import com.windowslockpin.companion.core.transport.FakeTransportConnection
import com.windowslockpin.companion.core.transport.TransportConnection
import com.windowslockpin.companion.core.unlock.UnlockCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class UnlockSessionManagerTest {

    private val pcId = PcId.fromString("abcdef0123456789abcdef0123456789")
    private val pcName = "Host-Office"
    private val btMac = BluetoothMacAddress.parse("11:22:33:44:55:66")
    private val deviceId = DeviceId("android-test-device-uuid")
    private var currentTimeMs: Long = 1700000000000L

    private lateinit var stateMachine: CompanionStateMachine
    private lateinit var pairedRecord: PairedPcRecord
    private lateinit var coordinator: UnlockCoordinator

    @Before
    fun setUp() {
        UnlockSessionManager.resetForTests()

        pairedRecord = PairedPcRecord(
            pcId = pcId,
            pcName = pcName,
            bluetoothMac = btMac,
            serverPublicKey = ByteArray(65) { 0x04 },
            kPair = ByteArray(32),
            pairedTimestampMs = currentTimeMs
        )
        val initialStateMachine = CompanionStateMachine(
            initialPairedPcs = mapOf(pcId.value to pairedRecord),
            timeProvider = { currentTimeMs }
        )
        val deviceIdProvider = object : DeviceIdProvider {
            override fun getDeviceId(): DeviceId = deviceId
        }
        val initialCoordinator = UnlockCoordinator(deviceIdProvider, initialStateMachine)

        val shared = UnlockSessionManager.initializeIfNeeded(initialCoordinator, initialStateMachine)
        coordinator = shared.coordinator
        stateMachine = shared.stateMachine
    }

    @After
    fun tearDown() {
        UnlockSessionManager.resetForTests()
    }

    private fun createSession(
        reqId: RequestId = RequestId.generate(),
        ttlMs: Long = 30000L,
        challengeTsMs: Long = System.currentTimeMillis(),
        transport: TransportConnection = FakeTransportConnection.createPair().first,
        receivedElapsedMs: Long = android.os.SystemClock.elapsedRealtime()
    ): ActiveUnlockSession {
        val nonce = ChallengeNonce.generate(32)
        val challenge = UnlockChallenge(
            pcId = pcId,
            challengeNonce = nonce,
            timestampMs = challengeTsMs,
            ttlMs = ttlMs,
            userDisplayName = "Alice"
        )
        val transcript = ByteArray(64) { 0xAA.toByte() }
        return ActiveUnlockSession(
            pcRecord = pairedRecord,
            requestId = reqId,
            challenge = challenge,
            connection = transport,
            transcript = transcript,
            receivedElapsedMs = receivedElapsedMs
        )
    }

    @Test
    fun testInitializeIfNeededDoesNotOverwrite() {
        val candidateSm = CompanionStateMachine()
        val dummyDeviceId = object : DeviceIdProvider {
            override fun getDeviceId(): DeviceId = DeviceId("dummy-device")
        }
        val candidateCoord = UnlockCoordinator(dummyDeviceId, candidateSm)

        // Attempting to re-initialize must return the previously initialized instances without replacing them
        val shared = UnlockSessionManager.initializeIfNeeded(candidateCoord, candidateSm)
        assertSame(coordinator, shared.coordinator)
        assertSame(stateMachine, shared.stateMachine)
        assertNotSame(candidateCoord, shared.coordinator)
        assertNotSame(candidateSm, shared.stateMachine)
    }

    @Test
    fun testSingleActiveSessionConstraint() = runBlocking {
        val session1 = createSession()
        val registered1 = UnlockSessionManager.registerSession(session1)
        assertTrue(registered1)
        assertEquals(session1, UnlockSessionManager.activeSession.value)

        // Attempting to register a second session while first is active must be rejected
        val session2 = createSession()
        val registered2 = UnlockSessionManager.registerSession(session2)
        assertFalse(registered2)
        assertEquals(session1, UnlockSessionManager.activeSession.value)

        // After cancelling session1, registering a new session succeeds
        UnlockSessionManager.cancelActiveSession()
        assertNull(UnlockSessionManager.activeSession.value)

        val session3 = createSession()
        val registered3 = UnlockSessionManager.registerSession(session3)
        assertTrue(registered3)
        assertEquals(session3, UnlockSessionManager.activeSession.value)

        UnlockSessionManager.cancelActiveSession()
    }

    @Test
    fun testReplayAndDuplicateSigningPrevention() = runBlocking {
        val reqId = RequestId.generate()
        val session = createSession(reqId = reqId)
        assertTrue(UnlockSessionManager.registerSession(session))

        // First attempt to start signing succeeds
        val canSign1 = UnlockSessionManager.tryStartSigning(reqId)
        assertTrue(canSign1)
        assertTrue(session.isSigning)

        // Concurrent attempt while signing in progress fails
        val canSignConcurrent = UnlockSessionManager.tryStartSigning(reqId)
        assertFalse(canSignConcurrent)

        // Mark session as signed
        val marked = UnlockSessionManager.markSigned(reqId)
        assertTrue(marked)
        assertTrue(session.isSigned)
        assertFalse(session.isSigning)

        // Subsequent attempt to sign the same session is blocked
        val canSignReplay = UnlockSessionManager.tryStartSigning(reqId)
        assertFalse(canSignReplay)

        UnlockSessionManager.cancelActiveSession()
    }

    @Test
    fun testSessionCancellationZerosTranscript() = runBlocking {
        val session = createSession()
        UnlockSessionManager.registerSession(session)
        assertFalse(session.transcript.all { it == 0.toByte() })

        UnlockSessionManager.cancelActiveSession(reasonText = "Test cancel")
        assertNull(UnlockSessionManager.activeSession.value)

        // Transcript must be zeroized for security
        assertTrue(session.transcript.all { it == 0.toByte() })
    }

    @Test
    fun testExpiredSessionCannotBeSigned() = runBlocking {
        val expiredSession = createSession(
            ttlMs = 1000L,
            receivedElapsedMs = android.os.SystemClock.elapsedRealtime() - 5000L
        )
        UnlockSessionManager.registerSession(expiredSession)

        val canSign = UnlockSessionManager.tryStartSigning(expiredSession.requestId)
        assertFalse(canSign)

        UnlockSessionManager.cancelActiveSession()
    }

    @Test
    fun testSessionExpiresWhenMonotonicDeadlineIsReached() = runBlocking {
        val session = createSession(ttlMs = 1000L, challengeTsMs = System.currentTimeMillis() + 1000L)
        assertTrue(UnlockSessionManager.registerSession(session))
        assertEquals(1000L, session.remainingTimeMs())
        ShadowSystemClock.advanceBy(Duration.ofMillis(999))
        assertEquals(1L, session.remainingTimeMs())
        assertFalse(session.isExpired)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        assertEquals(0L, session.remainingTimeMs())
        assertTrue(session.isExpired)
        assertFalse(UnlockSessionManager.tryStartSigning(session.requestId))
        UnlockSessionManager.cancelActiveSession()
    }

    @Test
    fun testThirtySecondRequestSurvivesHostClockSkew() = runBlocking {
        // Exercise the same validation + session registration path as the service.
        // A host 29.4 seconds behind used to leave only 600 ms for confirmation.
        for (hostOffsetMs in listOf(-29_400L, -86_400_000L, 86_400_000L)) {
            currentTimeMs = System.currentTimeMillis()
            val session = createSession(challengeTsMs = currentTimeMs + hostOffsetMs)
            assertTrue(coordinator.processIncomingChallenge(session.requestId, session.challenge, pairedRecord).isSuccess)
            assertTrue(UnlockSessionManager.registerSession(session))
            assertEquals(30_000L, session.remainingTimeMs())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
            assertEquals(25_000L, session.remainingTimeMs())
            assertTrue(UnlockSessionManager.tryStartSigning(session.requestId))
            UnlockSessionManager.cancelSigning(session.requestId)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(25))
            assertTrue(session.isExpired)
            assertFalse(UnlockSessionManager.tryStartSigning(session.requestId))
            UnlockSessionManager.cancelActiveSession()
        }
    }

    @Test
    fun testNotificationRequestIdMismatchGuard() {
        val validReqId = RequestId.generate()
        val otherReqId = RequestId.generate()
        val session = createSession(reqId = validReqId)

        // Matching requestId on unexpired session succeeds
        assertTrue(UnlockSessionManager.shouldTriggerBiometricForNotification(session, validReqId.value))

        // Mismatched requestId is rejected
        assertFalse(UnlockSessionManager.shouldTriggerBiometricForNotification(session, otherReqId.value))

        // Missing extra (-1) is rejected
        assertFalse(UnlockSessionManager.shouldTriggerBiometricForNotification(session, -1L))

        // Null session is rejected
        assertFalse(UnlockSessionManager.shouldTriggerBiometricForNotification(null, validReqId.value))

        // Expired session is rejected
        val expiredSession = createSession(
            reqId = validReqId,
            ttlMs = 1000L,
            receivedElapsedMs = android.os.SystemClock.elapsedRealtime() - 5000L
        )
        assertFalse(UnlockSessionManager.shouldTriggerBiometricForNotification(expiredSession, validReqId.value))
    }

    @Test
    fun testSubmitBiometricSignatureCompletesIndependentlyAndZeroesSignature() = runBlocking {
        currentTimeMs = System.currentTimeMillis()
        val (clientTransport, serverTransport) = FakeTransportConnection.createPair()
        val reqId = RequestId.generate()
        val session = createSession(reqId = reqId, challengeTsMs = currentTimeMs, transport = clientTransport)

        // Transition state machine into ChallengeReceived
        val challengeResult = coordinator.processIncomingChallenge(reqId, session.challenge, pairedRecord)
        assertTrue(challengeResult.isSuccess)

        assertTrue(UnlockSessionManager.registerSession(session))
        assertTrue(UnlockSessionManager.tryStartSigning(reqId))

        // Server-side mock responding to UNLOCK_RESPONSE with UNLOCK_RESULT (accepted)
        val serverJob = launch {
            val responseFrame = serverTransport.receiveFrame()
            assertEquals(ProtocolConstants.MSG_UNLOCK_RESPONSE, responseFrame.header.messageType)
            val resultMsg = UnlockResult(UnlockResult.STATUS_ACCEPTED, "Windows unlocked successfully")
            serverTransport.sendFrame(resultMsg.toFrame(reqId))
        }

        // Subscribe before submission. UI events are intentionally not replayed:
        // reopening the app must never surface an old Accepted result.
        val acceptedEvent = async {
            withTimeout(2_000L) {
                UnlockSessionManager.uiEvents.first { it is UnlockUiEvent.Accepted }
            }
        }
        yield()

        val derSignature = ByteArray(70) { 0x30 }
        val submitted = UnlockSessionManager.submitBiometricSignature(reqId, derSignature)
        assertTrue(submitted)

        // Wait for background worker in managerScope to complete
        UnlockSessionManager.awaitBackgroundCompletion()
        serverJob.join()

        // Verify latest UI event emitted
        val latestEvent = acceptedEvent.await()
        assertTrue(latestEvent is UnlockUiEvent.Accepted)
        assertEquals(reqId, (latestEvent as UnlockUiEvent.Accepted).requestId)
        assertEquals("Windows unlocked successfully", latestEvent.message)

        // Verify signature and transcript zeroization
        assertTrue(derSignature.all { it == 0.toByte() })
        assertTrue(session.transcript.all { it == 0.toByte() })

        // Session cleared after completion
        assertNull(UnlockSessionManager.activeSession.value)
    }
}
