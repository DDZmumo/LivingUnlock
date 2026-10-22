package com.windowslockpin.companion.core.model

import java.util.UUID

object ProtocolConstants {
    const val PROTOCOL_VERSION: Byte = 1

    const val MAX_FRAME_SIZE: Int = 8192
    const val HEADER_SIZE: Int = 16
    const val MAX_PAYLOAD_SIZE: Int = MAX_FRAME_SIZE - HEADER_SIZE // 8176 bytes

    const val MAX_URI_LENGTH: Int = 512
    const val MAX_IDENTIFIER_LENGTH: Int = 64
    const val MIN_IDENTIFIER_LENGTH: Int = 16
    const val MAX_NAME_LENGTH: Int = 64
    const val MAX_ERROR_MESSAGE_LENGTH: Int = 128

    const val PAIRING_URI_SCHEME: String = "wslp"
    const val PAIRING_URI_HOST: String = "pair"

    val RFCOMM_SERVICE_UUID: UUID = UUID.fromString("9b3f4a10-7c22-4e89-80b1-5d9c71a3d0f2")

    val MAGIC_BYTE_0: Byte = 0x57.toByte() // 'W'
    val MAGIC_BYTE_1: Byte = 0x4C.toByte() // 'L'

    const val MSG_PAIR_REQUEST: Byte = 0x01
    const val MSG_PAIR_RESPONSE: Byte = 0x02
    const val MSG_UNLOCK_CHALLENGE: Byte = 0x03
    const val MSG_UNLOCK_RESPONSE: Byte = 0x04
    const val MSG_CANCEL: Byte = 0x05
    const val MSG_ERROR: Byte = 0x06
    const val MSG_PING: Byte = 0x07
    const val MSG_PONG: Byte = 0x08
    const val MSG_UNLOCK_RESULT: Byte = 0x09

    const val UNLOCK_RESULT_STATUS_ACCEPTED = 0
    const val UNLOCK_RESULT_STATUS_REJECTED = 1
    const val UNLOCK_RESULT_STATUS_ERROR = 2

    const val MAX_QR_EXPIRY_WINDOW_SEC: Long = 180L // Max acceptable QR code validity
    const val CANONICAL_TRANSCRIPT_PREFIX: String = "WSLP-V1-UNLOCK-TRANSCRIPT\u0000"
}
