package com.windowslockpin.companion.core.statemachine

import com.windowslockpin.companion.core.crypto.CryptoEngine
import com.windowslockpin.companion.core.model.*
import java.util.concurrent.ConcurrentHashMap

sealed class CompanionState {
    object Unpaired : CompanionState() {
        override fun toString(): String = "Unpaired"
    }

    data class PairingConnecting(val targetUri: PairingUri) : CompanionState()

    data class PairingRequested(val targetUri: PairingUri, val requestId: RequestId) : CompanionState()

    data class PairedIdle(val pairedPcs: Map<String, PairedPcRecord>) : CompanionState()

    data class ChallengeReceived(
        val pcRecord: PairedPcRecord,
        val challenge: UnlockChallenge,
        val requestId: RequestId
    ) : CompanionState()

    data class BiometricPrompting(
        val pcRecord: PairedPcRecord,
        val challenge: UnlockChallenge,
        val requestId: RequestId
    ) : CompanionState()

    data class UnlockResponded(
        val pcRecord: PairedPcRecord,
        val requestId: RequestId,
        val success: Boolean
    ) : CompanionState()

    data class AwaitingUnlockResult(
        val pcRecord: PairedPcRecord,
        val requestId: RequestId
    ) : CompanionState()

    data class UnlockCompleted(
        val pcRecord: PairedPcRecord,
        val requestId: RequestId,
        val result: UnlockResult
    ) : CompanionState()

    data class Error(val code: ProtocolErrorCode, val message: String) : CompanionState()
}

data class PairedPcRecord(
    val pcId: PcId,
    val pcName: String,
    val bluetoothMac: BluetoothMacAddress,
    val serverPublicKey: ByteArray,
    val kPair: ByteArray = ByteArray(0),
    val pairedTimestampMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairedPcRecord) return false
        return pcId == other.pcId &&
                pcName == other.pcName &&
                bluetoothMac == other.bluetoothMac &&
                serverPublicKey.contentEquals(other.serverPublicKey) &&
                kPair.contentEquals(other.kPair)
    }

    override fun hashCode(): Int {
        var result = pcId.hashCode()
        result = 31 * result + pcName.hashCode()
        result = 31 * result + bluetoothMac.hashCode()
        result = 31 * result + serverPublicKey.contentHashCode()
        result = 31 * result + kPair.contentHashCode()
        return result
    }

    override fun toString(): String {
        val maskedPc = if (pcId.value.length > 8) "${pcId.value.take(4)}...${pcId.value.takeLast(4)}" else pcId.value
        return "PairedPcRecord(pcId=$maskedPc, name='$pcName', mac=$bluetoothMac, kPair=[REDACTED])"
    }
}

class ReplayCache(private val maxEntries: Int = 1000) {
    private val seenNonces = ConcurrentHashMap<String, Long>()

    fun checkAndAdd(nonce: ChallengeNonce, nowMs: Long, expiryWindowMs: Long = 300_000L): Boolean {
        // Clean expired entries first if reaching limit
        if (seenNonces.size > maxEntries) {
            val cutoff = nowMs - expiryWindowMs
            seenNonces.entries.removeIf { it.value < cutoff }
        }

        val key = nonceKey(nonce)
        val prev = seenNonces.putIfAbsent(key, nowMs)
        return prev == null // true if newly added, false if already seen
    }

    fun contains(nonce: ChallengeNonce): Boolean = seenNonces.containsKey(nonceKey(nonce))

    fun clear() = seenNonces.clear()

    private fun nonceKey(nonce: ChallengeNonce): String =
        nonce.bytes.joinToString("") { byte -> "%02x".format(byte) }
}

class CompanionStateMachine(
    initialPairedPcs: Map<String, PairedPcRecord> = emptyMap(),
    private val replayCache: ReplayCache = ReplayCache(),
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val pairedPcs = ConcurrentHashMap<String, PairedPcRecord>(initialPairedPcs)

    var currentState: CompanionState = if (pairedPcs.isEmpty()) CompanionState.Unpaired else CompanionState.PairedIdle(pairedPcs.toMap())
        private set

    fun getPairedPcs(): Map<String, PairedPcRecord> = pairedPcs.toMap()

    fun removePairedPc(pcId: PcId): Boolean {
        val removed = pairedPcs.remove(pcId.value) != null
        if (pairedPcs.isEmpty()) {
            currentState = CompanionState.Unpaired
        } else if (currentState is CompanionState.PairedIdle) {
            currentState = CompanionState.PairedIdle(pairedPcs.toMap())
        }
        return removed
    }

    @Synchronized
    fun onQrScanned(uri: PairingUri): ProtocolResult<Unit> {
        val nowSec = timeProvider() / 1000L
        if (uri.isExpired(nowSec)) {
            SafeLogger.w(TAG, "Scanned QR code is expired")
            return ProtocolResult.Failure(ProtocolErrorCode.EXPIRED, "Pairing QR code has expired")
        }

        currentState = CompanionState.PairingConnecting(uri)
        SafeLogger.i(TAG, "Initiated pairing connection to PC")
        return ProtocolResult.Success(Unit)
    }

    @Synchronized
    fun onPairRequestSent(requestId: RequestId): ProtocolResult<Unit> {
        val state = currentState as? CompanionState.PairingConnecting
            ?: return ProtocolResult.Failure(ProtocolErrorCode.UNEXPECTED_STATE, "Cannot send pair request in state $currentState")

        currentState = CompanionState.PairingRequested(state.targetUri, requestId)
        return ProtocolResult.Success(Unit)
    }

    @Synchronized
    fun onPairResponseReceived(
        requestId: RequestId,
        response: PairResponse,
        kPair: ByteArray = ByteArray(0),
        expectedConfirmationTag: ByteArray? = null
    ): ProtocolResult<PairedPcRecord> {
        val state = currentState as? CompanionState.PairingRequested
            ?: return ProtocolResult.Failure(ProtocolErrorCode.UNEXPECTED_STATE, "Not awaiting pair response")

        if (state.requestId != requestId) {
            return ProtocolResult.Failure(ProtocolErrorCode.DEVICE_MISMATCH, "Request ID mismatch on pair response")
        }

        if (!response.isSuccess) {
            currentState = if (pairedPcs.isEmpty()) CompanionState.Unpaired else CompanionState.PairedIdle(pairedPcs.toMap())
            return ProtocolResult.Failure(ProtocolErrorCode.CRYPTO_FAILURE, "Host rejected pairing with status ${response.statusCode}")
        }

        if (expectedConfirmationTag != null && !CryptoEngine.constantTimeEquals(expectedConfirmationTag, response.confirmationTag)) {
            currentState = if (pairedPcs.isEmpty()) CompanionState.Unpaired else CompanionState.PairedIdle(pairedPcs.toMap())
            return ProtocolResult.Failure(ProtocolErrorCode.CRYPTO_FAILURE, "Pairing confirmation tag verification failed")
        }

        val record = PairedPcRecord(
            pcId = state.targetUri.pcId,
            pcName = state.targetUri.pcName,
            bluetoothMac = state.targetUri.bluetoothMac,
            serverPublicKey = response.serverPublicKey,
            kPair = kPair.copyOf(),
            pairedTimestampMs = timeProvider()
        )
        pairedPcs[record.pcId.value] = record
        currentState = CompanionState.PairedIdle(pairedPcs.toMap())
        SafeLogger.i(TAG, "Successfully paired PC")
        return ProtocolResult.Success(record)
    }

    @Synchronized
    fun onUnlockChallengeReceived(
        requestId: RequestId,
        challenge: UnlockChallenge,
        expectedPcId: PcId? = null
    ): ProtocolResult<UnlockChallenge> {
        if (currentState !is CompanionState.PairedIdle) {
            SafeLogger.w(TAG, "Cannot accept unlock challenge; machine is in state: $currentState")
            return ProtocolResult.Failure(
                ProtocolErrorCode.UNEXPECTED_STATE,
                "Another request is currently in progress (state: $currentState)"
            )
        }

        if (expectedPcId != null && challenge.pcId != expectedPcId) {
            SafeLogger.w(TAG, "Challenge pcId ${challenge.pcId} does not match expected pcId $expectedPcId")
            return ProtocolResult.Failure(
                ProtocolErrorCode.DEVICE_MISMATCH,
                "Challenge pcId does not match connected PC"
            )
        }

        val record = pairedPcs[challenge.pcId.value]
            ?: return ProtocolResult.Failure(ProtocolErrorCode.DEVICE_MISMATCH, "Received challenge from unknown PC")

        val nowMs = timeProvider()
        // The PC's epoch timestamp is an opaque part of the signed transcript,
        // not a timestamp in the phone's clock domain. Windows rejects responses
        // past its own monotonic deadline; the app uses a receipt-based TTL.

        val isFresh = replayCache.checkAndAdd(challenge.challengeNonce, nowMs)
        if (!isFresh) {
            SafeLogger.w(TAG, "Replayed challenge nonce detected!")
            return ProtocolResult.Failure(ProtocolErrorCode.REPLAY_DETECTED, "Challenge nonce was previously used")
        }

        currentState = CompanionState.ChallengeReceived(record, challenge, requestId)
        SafeLogger.i(TAG, "Valid unlock challenge received, ready for biometric confirmation")
        return ProtocolResult.Success(challenge)
    }

    @Synchronized
    fun onBiometricPromptStarted(): ProtocolResult<Unit> {
        val state = currentState as? CompanionState.ChallengeReceived
            ?: return ProtocolResult.Failure(ProtocolErrorCode.UNEXPECTED_STATE, "No active challenge to prompt for")

        currentState = CompanionState.BiometricPrompting(state.pcRecord, state.challenge, state.requestId)
        return ProtocolResult.Success(Unit)
    }

    @Synchronized
    fun onBiometricPromptCompleted(success: Boolean): ProtocolResult<Unit> {
        val state = currentState as? CompanionState.BiometricPrompting
            ?: return ProtocolResult.Failure(ProtocolErrorCode.UNEXPECTED_STATE, "No active biometric prompt")

        currentState = CompanionState.UnlockResponded(state.pcRecord, state.requestId, success)
        return ProtocolResult.Success(Unit)
    }

    @Synchronized
    fun onUnlockResponseSent(requestId: RequestId): ProtocolResult<Unit> {
        val (pcRecord, expectedReqId) = when (val state = currentState) {
            is CompanionState.UnlockResponded -> Pair(state.pcRecord, state.requestId)
            is CompanionState.BiometricPrompting -> Pair(state.pcRecord, state.requestId)
            else -> return ProtocolResult.Failure(
                ProtocolErrorCode.UNEXPECTED_STATE,
                "Cannot transition to awaiting unlock result from $currentState"
            )
        }
        if (expectedReqId != requestId) {
            return ProtocolResult.Failure(ProtocolErrorCode.DEVICE_MISMATCH, "Request ID mismatch on unlock response")
        }
        currentState = CompanionState.AwaitingUnlockResult(pcRecord, requestId)
        return ProtocolResult.Success(Unit)
    }

    @Synchronized
    fun onUnlockResultReceived(
        requestId: RequestId,
        result: UnlockResult
    ): ProtocolResult<UnlockResult> {
        val (pcRecord, expectedReqId) = when (val state = currentState) {
            is CompanionState.AwaitingUnlockResult -> Pair(state.pcRecord, state.requestId)
            is CompanionState.UnlockResponded -> Pair(state.pcRecord, state.requestId)
            else -> return ProtocolResult.Failure(
                ProtocolErrorCode.UNEXPECTED_STATE,
                "Not awaiting unlock result (current state: $currentState)"
            )
        }
        if (expectedReqId != requestId) {
            return ProtocolResult.Failure(ProtocolErrorCode.DEVICE_MISMATCH, "Request ID mismatch on unlock result")
        }
        currentState = CompanionState.UnlockCompleted(pcRecord, requestId, result)
        SafeLogger.i(TAG, "Unlock result received: statusCode=${result.statusCode}")
        return ProtocolResult.Success(result)
    }

    @Synchronized
    fun onUnlockCycleFinished() {
        currentState = CompanionState.PairedIdle(pairedPcs.toMap())
    }

    @Synchronized
    fun onCancelled(reason: String) {
        SafeLogger.i(TAG, "Operation cancelled: $reason")
        currentState = if (pairedPcs.isEmpty()) CompanionState.Unpaired else CompanionState.PairedIdle(pairedPcs.toMap())
    }

    companion object {
        private const val TAG = "CompanionStateMachine"
    }
}
