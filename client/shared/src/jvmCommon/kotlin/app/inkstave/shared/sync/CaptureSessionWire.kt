package app.inkstave.shared.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * [CaptureSessionMessage]'s wire encoding: a 1-byte type discriminant followed by that message's
 * own fields, all inside one [MessageFraming] frame. Kept as a tiny hand-written codec, not
 * `kotlinx.serialization`'s binary format(s) -- `Photo.bytes` (a raw photo, up to several
 * megabytes) going through a general-purpose serialization framework's binary encoding would add
 * real overhead this simple, fixed, three-message-type protocol doesn't need; `PickedFile`/`.smpk`
 * elsewhere in this codebase use `kotlinx.serialization` because *their* payloads are genuinely
 * general-shaped JSON documents, not because it's this project's only acceptable encoding.
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
            TYPE_SESSION_START -> CaptureSessionMessage.SessionStart(sessionId = data.readUTF(), scoreTitle = data.readUTF())
            TYPE_PHOTO -> {
                val sessionId = data.readUTF()
                val sequenceIndex = data.readInt()
                val photoBytes = ByteArray(data.readInt())
                data.readFully(photoBytes)
                CaptureSessionMessage.Photo(sessionId, sequenceIndex, photoBytes)
            }
            TYPE_SESSION_END -> CaptureSessionMessage.SessionEnd(sessionId = data.readUTF())
            else -> error("unknown CaptureSessionMessage wire type $type")
        }
    }
}
