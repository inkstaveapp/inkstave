package app.inkstave.shared.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Wire encoding of [CaptureSessionMessage]: a 1-byte type followed by the message's fields, inside one
 * [MessageFraming] frame. Hand-written rather than kotlinx.serialization to avoid overhead on
 * multi-megabyte photo payloads in a fixed three-message protocol.
 */
object CaptureSessionWire {
    private const val TYPE_SESSION_START: Byte = 1
    private const val TYPE_PHOTO: Byte = 2
    private const val TYPE_SESSION_END: Byte = 3

    fun encode(message: CaptureSessionMessage): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        when (message) {
            is CaptureSessionMessage.SessionStart -> {
                data.writeByte(TYPE_SESSION_START.toInt())
                data.writeUTF(message.sessionId)
                data.writeUTF(message.scoreTitle)
            }

            is CaptureSessionMessage.Photo -> {
                data.writeByte(TYPE_PHOTO.toInt())
                data.writeUTF(message.sessionId)
                data.writeInt(message.sequenceIndex)
                data.writeInt(message.bytes.size)
                data.write(message.bytes)
            }

            is CaptureSessionMessage.SessionEnd -> {
                data.writeByte(TYPE_SESSION_END.toInt())
                data.writeUTF(message.sessionId)
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): CaptureSessionMessage {
        val data = DataInputStream(ByteArrayInputStream(bytes))
        return when (val type = data.readByte()) {
            TYPE_SESSION_START -> {
                CaptureSessionMessage.SessionStart(sessionId = data.readUTF(), scoreTitle = data.readUTF())
            }

            TYPE_PHOTO -> {
                val sessionId = data.readUTF()
                val sequenceIndex = data.readInt()
                val photoBytes = ByteArray(data.readInt())
                data.readFully(photoBytes)
                CaptureSessionMessage.Photo(sessionId, sequenceIndex, photoBytes)
            }

            TYPE_SESSION_END -> {
                CaptureSessionMessage.SessionEnd(sessionId = data.readUTF())
            }

            else -> {
                error("unknown CaptureSessionMessage wire type $type")
            }
        }
    }
}
