package com.windowslockpin.companion.core.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction

private fun decodeUtf8Strict(bytes: ByteArray): String =
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString()

sealed class ProtocolMessage {
    abstract val messageType: Byte
    abstract fun encodePayload(): ByteArray

    fun toFrame(requestId: RequestId): Frame =
        Frame.create(messageType, requestId, encodePayload())

    companion object {
        fun decode(frame: Frame): ProtocolMessage {
            val dis = DataInputStream(ByteArrayInputStream(frame.payload))
            val decoded = when (frame.header.messageType) {
                ProtocolConstants.MSG_PAIR_REQUEST -> PairRequest.decode(dis)
                ProtocolConstants.MSG_PAIR_RESPONSE -> PairResponse.decode(dis)
                ProtocolConstants.MSG_UNLOCK_CHALLENGE -> UnlockChallenge.decode(dis)
                ProtocolConstants.MSG_UNLOCK_RESPONSE -> UnlockResponse.decode(dis)
                ProtocolConstants.MSG_CANCEL -> CancelMessage.decode(dis)
                ProtocolConstants.MSG_ERROR -> ErrorMessage.decode(dis)
                ProtocolConstants.MSG_PING -> PingMessage.decode(dis)
                ProtocolConstants.MSG_PONG -> PongMessage.decode(dis)
                ProtocolConstants.MSG_UNLOCK_RESULT -> UnlockResult.decode(dis)
                else -> throw UnknownMessageTypeException("Unknown message type: 0x%02X".format(frame.header.messageType))
            }
            if (dis.available() != 0) {
                throw MalformedFrameException("Message payload contains ${dis.available()} trailing bytes")
            }
            return decoded
        }
    }
}

data class PairRequest(
    val deviceId: DeviceId,
    val deviceName: String,
    val pairingToken: PairingToken,
    val clientPublicKey: ByteArray
) : ProtocolMessage() {
    override val messageType: Byte = ProtocolConstants.MSG_PAIR_REQUEST

    init {
        require(deviceName.toByteArray(StandardCharsets.UTF_8).size in 1..ProtocolConstants.MAX_NAME_LENGTH) {
            "Device name UTF-8 length must be 1..${ProtocolConstants.MAX_NAME_LENGTH} bytes"
        }
        require(clientPublicKey.size in 33..65) {
            "Client public key size must be 33..65 bytes, got ${clientPublicKey.size}"
        }
    }

    override fun encodePayload(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        val devIdBytes = deviceId.value.toByteArray(StandardCharsets.UTF_8)
        dos.writeShort(devIdBytes.size)
        dos.write(devIdBytes)

        val nameBytes = deviceName.toByteArray(StandardCharsets.UTF_8)
        dos.writeShort(nameBytes.size)
        dos.write(nameBytes)

        dos.writeShort(pairingToken.bytes.size)
        dos.write(pairingToken.bytes)

        dos.writeShort(clientPublicKey.size)
        dos.write(clientPublicKey)
        dos.flush()
        return baos.toByteArray()
    }

    override fun toString(): String {
        return "PairRequest(deviceId=$deviceId, deviceName='$deviceName', pairingToken=[REDACTED], clientPublicKey=${clientPublicKey.size} bytes)"
    }

    companion object {
        fun decode(dis: DataInputStream): PairRequest {
            val devIdLen = dis.readUnsignedShort()
            if (devIdLen !in 1..ProtocolConstants.MAX_IDENTIFIER_LENGTH) throw MalformedFrameException("Invalid device ID length")
            val devIdBytes = ByteArray(devIdLen)
            dis.readFully(devIdBytes)
            val devId = DeviceId(decodeUtf8Strict(devIdBytes))

            val nameLen = dis.readUnsignedShort()
            if (nameLen !in 1..ProtocolConstants.MAX_NAME_LENGTH) throw MalformedFrameException("Invalid device name length")
            val nameBytes = ByteArray(nameLen)
            dis.readFully(nameBytes)
            val name = decodeUtf8Strict(nameBytes)

            val tokenLen = dis.readUnsignedShort()
            if (tokenLen !in 16..32) throw MalformedFrameException("Invalid pairing token length")
            val tokenBytes = ByteArray(tokenLen)
            dis.readFully(tokenBytes)
            val token = PairingToken(tokenBytes)

            val keyLen = dis.readUnsignedShort()
            if (keyLen !in 33..65) throw MalformedFrameException("Invalid client public key length")
            val keyBytes = ByteArray(keyLen)
            dis.readFully(keyBytes)

            return PairRequest(devId, name, token, keyBytes)
        }
    }
}

data class PairResponse(
    val statusCode: Int, // 0 = SUCCESS
    val serverPublicKey: ByteArray,
    val confirmationTag: ByteArray
) : ProtocolMessage() {
    override val messageType: Byte = ProtocolConstants.MSG_PAIR_RESPONSE

    val isSuccess: Boolean get() = statusCode == 0

    init {
        if (isSuccess) {
            require(serverPublicKey.size in 33..65) {
                "Server public key size must be 33..65 bytes, got ${serverPublicKey.size}"
            }
            require(confirmationTag.size == 32) {
                "Confirmation tag must be 32 bytes, got ${confirmationTag.size}"
            }
        } else {
            require(serverPublicKey.isEmpty() && confirmationTag.isEmpty()) {
                "Failed pairing response must not contain key material"
            }
        }
    }

    override fun encodePayload(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(statusCode)
        dos.writeShort(serverPublicKey.size)
        dos.write(serverPublicKey)
        dos.writeShort(confirmationTag.size)
        dos.write(confirmationTag)
        dos.flush()
        return baos.toByteArray()
    }

    override fun toString(): String {
        return "PairResponse(statusCode=$statusCode, serverPublicKey=${serverPublicKey.size} bytes, confirmationTag=[REDACTED])"
    }

    companion object {
        fun decode(dis: DataInputStream): PairResponse {
            val status = dis.readUnsignedShort()
            val keyLen = dis.readUnsignedShort()
            if (keyLen > 65) throw MalformedFrameException("Invalid server public key length")
            val keyBytes = ByteArray(keyLen)
            dis.readFully(keyBytes)
            val tagLen = dis.readUnsignedShort()
            if (tagLen > 32) throw MalformedFrameException("Invalid confirmation tag length")
            val tagBytes = ByteArray(tagLen)
            dis.readFully(tagBytes)
            return PairResponse(status, keyBytes, tagBytes)
        }
    }
}

data class UnlockChallenge(
    val pcId: PcId,
    val challengeNonce: ChallengeNonce,
    val timestampMs: Long,
    val ttlMs: Long,
    val userDisplayName: String
) : ProtocolMessage() {
    override val messageType: Byte = ProtocolConstants.MSG_UNLOCK_CHALLENGE

    init {
        require(userDisplayName.toByteArray(StandardCharsets.UTF_8).size in 1..ProtocolConstants.MAX_NAME_LENGTH) {
            "User display name UTF-8 length must be 1..${ProtocolConstants.MAX_NAME_LENGTH} bytes"
        }
        require(ttlMs in 1_000L..300_000L) {
            "TTL ms out of range 1000..300000, got $ttlMs"
        }
    }

    override fun encodePayload(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        val pcIdBytes = pcId.value.toByteArray(StandardCharsets.UTF_8)
        dos.writeShort(pcIdBytes.size)
        dos.write(pcIdBytes)

        dos.writeShort(challengeNonce.bytes.size)
        dos.write(challengeNonce.bytes)

        dos.writeLong(timestampMs)
        dos.writeInt(ttlMs.toInt())

        val userBytes = userDisplayName.toByteArray(StandardCharsets.UTF_8)
        dos.writeShort(userBytes.size)
        dos.write(userBytes)

        dos.flush()
        return baos.toByteArray()
    }

    override fun toString(): String {
        val maskedPc = if (pcId.value.length > 8) "${pcId.value.take(4)}...${pcId.value.takeLast(4)}" else pcId.value
        return "UnlockChallenge(pcId=$maskedPc, challengeNonce=[REDACTED], timestampMs=$timestampMs, ttlMs=$ttlMs, user='$userDisplayName')"
    }

    companion object {
        fun decode(dis: DataInputStream): UnlockChallenge {
            val pcIdLen = dis.readUnsignedShort()
            if (pcIdLen !in 16..64) throw MalformedFrameException("Invalid PC ID length")
            val pcIdBytes = ByteArray(pcIdLen)
            dis.readFully(pcIdBytes)
            val pcId = PcId.fromString(decodeUtf8Strict(pcIdBytes))

            val nonceLen = dis.readUnsignedShort()
            if (nonceLen !in 16..32) throw MalformedFrameException("Invalid challenge nonce length")
            val nonceBytes = ByteArray(nonceLen)
            dis.readFully(nonceBytes)
            val nonce = ChallengeNonce(nonceBytes)

            val ts = dis.readLong()
            val ttl = dis.readInt().toLong() and 0xFFFFFFFFL

            val userLen = dis.readUnsignedShort()
            if (userLen !in 1..ProtocolConstants.MAX_NAME_LENGTH) throw MalformedFrameException("Invalid user display name length")
            val userBytes = ByteArray(userLen)
            dis.readFully(userBytes)
            val user = decodeUtf8Strict(userBytes)

            return UnlockChallenge(pcId, nonce, ts, ttl, user)
        }
    }
}

data class UnlockResponse(
    val statusCode: Int, // 0 = SUCCESS, 1 = USER_REJECTED, 2 = BIOMETRIC_FAILED, 3 = TIMEOUT
    val challengeNonce: ChallengeNonce,
    val authSignature: ByteArray
) : ProtocolMessage() {
    override val messageType: Byte = ProtocolConstants.MSG_UNLOCK_RESPONSE

    val isSuccess: Boolean get() = statusCode == 0

    init {
        require(statusCode in STATUS_SUCCESS..STATUS_TIMEOUT) { "Unknown unlock response status: $statusCode" }
        if (isSuccess) {
            require(authSignature.size in 68..72) {
                "ECDSA DER signature must be between 68 and 72 bytes, got ${authSignature.size}"
            }
        } else require(authSignature.isEmpty()) { "Failed unlock response must not contain a signature" }
    }

    override fun encodePayload(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(statusCode)
        dos.writeShort(challengeNonce.bytes.size)
        dos.write(challengeNonce.bytes)
        dos.writeShort(authSignature.size)
        dos.write(authSignature)
        dos.flush()
        return baos.toByteArray()
    }

    override fun toString(): String {
        return "UnlockResponse(statusCode=$statusCode, challengeNonce=[REDACTED], authSignature=${authSignature.size} bytes)"
    }

    companion object {
        const val STATUS_SUCCESS = 0
        const val STATUS_USER_REJECTED = 1
        const val STATUS_BIOMETRIC_FAILED = 2
        const val STATUS_TIMEOUT = 3

        fun decode(dis: DataInputStream): UnlockResponse {
            val status = dis.readUnsignedShort()
            val nonceLen = dis.readUnsignedShort()
            if (nonceLen !in 16..32) throw MalformedFrameException("Invalid response nonce length")
            val nonceBytes = ByteArray(nonceLen)
            dis.readFully(nonceBytes)
            val nonce = ChallengeNonce(nonceBytes)

            val sigLen = dis.readUnsignedShort()
            if (sigLen > 72) throw MalformedFrameException("Invalid response signature length")
            val sigBytes = ByteArray(sigLen)
            dis.readFully(sigBytes)

            return UnlockResponse(status, nonce, sigBytes)
        }
    }
}

data class CancelMessage(
    val reasonCode: Byte
) : ProtocolMessage() {
    override val messageType: Byte = ProtocolConstants.MSG_CANCEL

    override fun encodePayload(): ByteArray = byteArrayOf(reasonCode)

    init { require(reasonCode in REASON_USER_CANCELLED..REASON_SYSTEM_CANCELLED) { "Unknown cancellation reason" } }

    companion object {
        const val REASON_USER_CANCELLED: Byte = 1
        const val REASON_TIMEOUT: Byte = 2
        const val REASON_SYSTEM_CANCELLED: Byte = 3

        fun decode(dis: DataInputStream): CancelMessage {
            val code = dis.readByte()
            return CancelMessage(code)
        }
    }
}

data class ErrorMessage(
    val errorCode: Int,
    val errorMessage: String
) : ProtocolMessage() {
    override val messageType: Byte = ProtocolConstants.MSG_ERROR

    init {
        require(errorMessage.toByteArray(StandardCharsets.UTF_8).size <= ProtocolConstants.MAX_ERROR_MESSAGE_LENGTH) {
            "Error message exceeds max UTF-8 length ${ProtocolConstants.MAX_ERROR_MESSAGE_LENGTH} bytes"
        }
    }

    override fun encodePayload(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(errorCode)
        val msgBytes = errorMessage.toByteArray(StandardCharsets.UTF_8)
        dos.writeShort(msgBytes.size)
        dos.write(msgBytes)
        dos.flush()
        return baos.toByteArray()
    }

    companion object {
        fun decode(dis: DataInputStream): ErrorMessage {
            val code = dis.readUnsignedShort()
            val len = dis.readUnsignedShort()
            if (len > ProtocolConstants.MAX_ERROR_MESSAGE_LENGTH) throw MalformedFrameException("Invalid error message length")
            val msgBytes = ByteArray(len)
            dis.readFully(msgBytes)
            return ErrorMessage(code, decodeUtf8Strict(msgBytes))
        }
    }
}

class PingMessage : ProtocolMessage() {
    override val messageType: Byte = ProtocolConstants.MSG_PING
    override fun encodePayload(): ByteArray = ByteArray(0)
    override fun equals(other: Any?): Boolean = other is PingMessage
    override fun hashCode(): Int = messageType.toInt()

    companion object {
        fun decode(@Suppress("UNUSED_PARAMETER") dis: DataInputStream): PingMessage = PingMessage()
    }
}

class PongMessage : ProtocolMessage() {
    override val messageType: Byte = ProtocolConstants.MSG_PONG
    override fun encodePayload(): ByteArray = ByteArray(0)
    override fun equals(other: Any?): Boolean = other is PongMessage
    override fun hashCode(): Int = messageType.toInt()

    companion object {
        fun decode(@Suppress("UNUSED_PARAMETER") dis: DataInputStream): PongMessage = PongMessage()
    }
}

data class UnlockResult(
    val statusCode: Int, // 0 = ACCEPTED, 1 = REJECTED, 2 = ERROR
    val message: String
) : ProtocolMessage() {
    override val messageType: Byte = ProtocolConstants.MSG_UNLOCK_RESULT

    val isAccepted: Boolean get() = statusCode == STATUS_ACCEPTED

    init {
        require(statusCode in STATUS_ACCEPTED..STATUS_ERROR) { "Unknown unlock result status: $statusCode" }
        require(message.toByteArray(StandardCharsets.UTF_8).size <= ProtocolConstants.MAX_ERROR_MESSAGE_LENGTH) {
            "Result message exceeds max UTF-8 length ${ProtocolConstants.MAX_ERROR_MESSAGE_LENGTH} bytes"
        }
    }

    override fun encodePayload(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(statusCode)
        val msgBytes = message.toByteArray(StandardCharsets.UTF_8)
        dos.writeShort(msgBytes.size)
        dos.write(msgBytes)
        dos.flush()
        return baos.toByteArray()
    }

    override fun toString(): String {
        return "UnlockResult(statusCode=$statusCode, message='$message')"
    }

    companion object {
        const val STATUS_ACCEPTED = 0
        const val STATUS_REJECTED = 1
        const val STATUS_ERROR = 2

        fun decode(dis: DataInputStream): UnlockResult {
            val code = dis.readUnsignedShort()
            if (code !in STATUS_ACCEPTED..STATUS_ERROR) {
                throw MalformedFrameException("Unknown unlock result status: $code")
            }
            val len = dis.readUnsignedShort()
            if (len > ProtocolConstants.MAX_ERROR_MESSAGE_LENGTH) {
                throw MalformedFrameException("Invalid unlock result message length: $len")
            }
            val msgBytes = ByteArray(len)
            dis.readFully(msgBytes)
            return UnlockResult(code, decodeUtf8Strict(msgBytes))
        }
    }
}

class UnknownMessageTypeException(message: String) : ProtocolException(message)
