package com.windowslockpin.companion.service

import com.windowslockpin.companion.core.model.*
import com.windowslockpin.companion.core.statemachine.CompanionStateMachine
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import com.windowslockpin.companion.core.transport.TransportConnection
import com.windowslockpin.companion.core.unlock.UnlockCoordinator
import com.windowslockpin.companion.core.unlock.UnlockExecutionResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ActiveUnlockSession(
    val pcRecord: PairedPcRecord,
    val requestId: RequestId,
    val challenge: UnlockChallenge,
    val connection: TransportConnection,
    val transcript: ByteArray,
    val receivedElapsedMs: Long = android.os.SystemClock.elapsedRealtime(),
    @Volatile var isSigning: Boolean = false,
    @Volatile var isSigned: Boolean = false
) {
    // The host timestamp belongs to the PC's clock and is signed unchanged.
    // Windows enforces the authoritative deadline; our local UI/signing budget
    // starts at receipt and must never subtract two devices' wall clocks.
    private val deadlineElapsedMs = receivedElapsedMs + challenge.ttlMs
    val isExpired: Boolean
        get() = remainingTimeMs() == 0L

    fun remainingTimeMs(): Long =
        maxOf(0L, deadlineElapsedMs - android.os.SystemClock.elapsedRealtime())

    fun zeroizeTranscript() {
        transcript.fill(0)
    }
}

sealed interface UnlockUiEvent {
    data class WaitingForResult(val requestId: RequestId) : UnlockUiEvent
    data class Accepted(val requestId: RequestId, val message: String) : UnlockUiEvent
    data class Rejected(val requestId: RequestId, val message: String) : UnlockUiEvent
    data class Error(val requestId: RequestId, val message: String) : UnlockUiEvent
    data class Cancelled(val requestId: RequestId?, val reason: String) : UnlockUiEvent
}

data class SharedComponents(
    val coordinator: UnlockCoordinator,
    val stateMachine: CompanionStateMachine
)

object UnlockSessionManager {
    private const val TAG = "UnlockSessionManager"
    private val mutex = Mutex()

    private var managerJob = SupervisorJob()
    private var managerScope = CoroutineScope(Dispatchers.IO + managerJob)

    private val _activeSession = MutableStateFlow<ActiveUnlockSession?>(null)
    val activeSession: StateFlow<ActiveUnlockSession?> = _activeSession.asStateFlow()

    // UI events describe a live operation. Replaying a previous Accepted event
    // when the Activity is opened later would falsely report a new unlock.
    private val _uiEvents = MutableSharedFlow<UnlockUiEvent>(replay = 0, extraBufferCapacity = 16)
    val uiEvents: SharedFlow<UnlockUiEvent> = _uiEvents.asSharedFlow()

    @Volatile
    var isAppInForeground: Boolean = false

    private var coordinator: UnlockCoordinator? = null
    private var stateMachine: CompanionStateMachine? = null

    @Synchronized
    fun initializeIfNeeded(
        candidateCoordinator: UnlockCoordinator,
        candidateStateMachine: CompanionStateMachine
    ): SharedComponents {
        val currentCoord = coordinator
        val currentSm = stateMachine
        if (currentCoord != null && currentSm != null) {
            SafeLogger.d(TAG, "Reusing existing shared UnlockCoordinator and CompanionStateMachine")
            return SharedComponents(currentCoord, currentSm)
        }
        SafeLogger.i(TAG, "Initializing shared UnlockCoordinator and CompanionStateMachine")
        coordinator = candidateCoordinator
        stateMachine = candidateStateMachine
        return SharedComponents(candidateCoordinator, candidateStateMachine)
    }

    @Synchronized
    internal fun resetForTests() {
        managerJob.cancelChildren()
        _activeSession.value = null
        coordinator = null
        stateMachine = null
    }

    internal suspend fun awaitBackgroundCompletion() {
        val currentJobs = managerJob.children.toList()
        currentJobs.joinAll()
    }

    /**
     * Notification requestId mismatch guard: only triggers biometric if session exists,
     * is unexpired, and its requestId matches the extra from notification intent.
     */
    fun shouldTriggerBiometricForNotification(
        session: ActiveUnlockSession?,
        intentRequestId: Long
    ): Boolean {
        if (session == null || session.isExpired) return false
        if (intentRequestId == -1L) return false
        return session.requestId.value == intentRequestId
    }

    /**
     * Attempts to register a new incoming unlock session.
     * Enforces single active request constraint: if an active session already exists and has not expired,
     * the new session is rejected.
     */
    suspend fun registerSession(session: ActiveUnlockSession): Boolean = mutex.withLock {
        val current = _activeSession.value
        if (current != null && !current.isExpired) {
            SafeLogger.w(TAG, "Rejecting new session; an active unlock request is already in progress")
            return false
        }

        // Clean up any stale expired session
        if (current != null) {
            cleanupSessionInternal(current, "Expired previous session cleaned up")
        }

        _activeSession.value = session
        SafeLogger.i(TAG, "Registered new active unlock session for PC: ${session.pcRecord.pcName}")
        return true
    }

    /**
     * Safely marks that biometric signing has started.
     * Prevents multiple concurrent signature dialogs or re-signing after Activity recreation.
     */
    suspend fun tryStartSigning(requestId: RequestId): Boolean = mutex.withLock {
        val session = _activeSession.value ?: return false
        if (session.requestId != requestId) return false
        if (session.isSigned) {
            SafeLogger.w(TAG, "Replay/Duplicate signing prevented: session was already signed!")
            return false
        }
        if (session.isSigning) {
            SafeLogger.w(TAG, "Signing is already in progress")
            return false
        }
        if (session.isExpired) {
            SafeLogger.w(TAG, "Cannot sign expired challenge")
            return false
        }
        session.isSigning = true
        return true
    }

    suspend fun cancelSigning(requestId: RequestId) = mutex.withLock {
        val session = _activeSession.value ?: return
        if (session.requestId == requestId && !session.isSigned) {
            session.isSigning = false
        }
    }

    suspend fun markSigned(requestId: RequestId): Boolean = mutex.withLock {
        val session = _activeSession.value ?: return false
        if (session.requestId != requestId) return false
        session.isSigned = true
        session.isSigning = false
        return true
    }

    /**
     * Submits biometric DER signature. Moves sending UNLOCK_RESPONSE, awaiting UNLOCK_RESULT,
     * state transition, and socket cleanup to UnlockSessionManager's independent CoroutineScope.
     * Guaranteed cleanup and memory zeroization.
     */
    fun submitBiometricSignature(requestId: RequestId, signature: ByteArray): Boolean {
        val session = _activeSession.value ?: return false
        if (session.requestId != requestId || session.isExpired) {
            return false
        }

        synchronized(this) {
            if (session.isSigned) {
                SafeLogger.w(TAG, "Session already signed; duplicate submission rejected")
                return false
            }
            session.isSigned = true
            session.isSigning = false
        }

        val coord = coordinator ?: run {
            SafeLogger.e(TAG, "Cannot submit signature: UnlockCoordinator not initialized")
            return false
        }

        val signatureBytes = signature.clone()
        signature.fill(0)

        managerScope.launch {
            try {
                _uiEvents.emit(UnlockUiEvent.WaitingForResult(requestId))

                val sendResult = coord.sendUnlockResponse(
                    connection = session.connection,
                    requestId = requestId,
                    challengeNonce = session.challenge.challengeNonce,
                    authSignature = signatureBytes
                )

                if (sendResult is ProtocolResult.Failure) {
                    SafeLogger.w(TAG, "sendUnlockResponse failed: ${sendResult.message}")
                    _uiEvents.emit(UnlockUiEvent.Error(requestId, sendResult.message))
                    coord.cancel(
                        connection = session.connection,
                        requestId = requestId,
                        reasonCode = CancelMessage.REASON_SYSTEM_CANCELLED,
                        reasonText = "Send response failed: ${sendResult.message}"
                    )
                    return@launch
                }

                // Wait for UNLOCK_RESULT from Windows host
                when (val execResult = coord.awaitUnlockResult(session.connection, requestId)) {
                    is UnlockExecutionResult.Success -> {
                        _uiEvents.emit(UnlockUiEvent.Accepted(requestId, execResult.result.message))
                    }
                    is UnlockExecutionResult.Rejected -> {
                        _uiEvents.emit(UnlockUiEvent.Rejected(requestId, execResult.result.message))
                    }
                    is UnlockExecutionResult.HostError -> {
                        _uiEvents.emit(UnlockUiEvent.Error(requestId, execResult.result.message))
                    }
                    is UnlockExecutionResult.Failure -> {
                        _uiEvents.emit(UnlockUiEvent.Error(requestId, execResult.message))
                    }
                    is UnlockExecutionResult.Cancelled -> {
                        _uiEvents.emit(UnlockUiEvent.Cancelled(requestId, "Unlock cancelled"))
                    }
                }
            } catch (e: Exception) {
                SafeLogger.e(TAG, "Exception during background unlock completion", e)
                _uiEvents.emit(UnlockUiEvent.Error(requestId, "Unlock failure: ${e.message}"))
                try {
                    coord.cancel(
                        connection = session.connection,
                        requestId = requestId,
                        reasonCode = CancelMessage.REASON_SYSTEM_CANCELLED,
                        reasonText = "Background worker exception: ${e.javaClass.simpleName}"
                    )
                } catch (_: Exception) {}
            } finally {
                signatureBytes.fill(0)
                clearSession(session)
            }
        }
        return true
    }

    /**
     * Cancels the active session, sending CANCEL message to PC and resetting state.
     */
    suspend fun cancelActiveSession(
        reasonCode: Byte = CancelMessage.REASON_USER_CANCELLED,
        reasonText: String = "User cancelled",
        expectedRequestId: RequestId? = null
    ) = mutex.withLock {
        val session = _activeSession.value ?: return
        if (expectedRequestId != null && session.requestId != expectedRequestId) return
        _uiEvents.emit(UnlockUiEvent.Cancelled(session.requestId, reasonText))
        cleanupSessionInternal(session, reasonText, sendCancelFrame = true, reasonCode = reasonCode)
        _activeSession.value = null
    }

    /**
     * Clears active session after completion.
     */
    suspend fun clearSession(session: ActiveUnlockSession) = mutex.withLock {
        if (_activeSession.value == session) {
            cleanupSessionInternal(session, "Session completed", sendCancelFrame = false)
            _activeSession.value = null
        }
    }

    private suspend fun cleanupSessionInternal(
        session: ActiveUnlockSession,
        reason: String,
        sendCancelFrame: Boolean = false,
        reasonCode: Byte = CancelMessage.REASON_USER_CANCELLED
    ) {
        SafeLogger.i(TAG, "Cleaning up unlock session: $reason")
        if (sendCancelFrame) {
            try {
                coordinator?.cancel(session.connection, session.requestId, reasonCode, reason)
            } catch (e: Exception) {
                SafeLogger.w(TAG, "Error sending cancel frame: ${e.message}")
            }
        } else {
            stateMachine?.onUnlockCycleFinished()
        }
        try {
            session.connection.close()
        } catch (_: Exception) {}
        session.zeroizeTranscript()
    }
}
