package app.inkstave.shared.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Framing for every sync message over TLS: a 4-byte big-endian payload length, then the payload.
 * Message types live in the payload; no compression or chunking, since TLS already provides integrity
 * and a photo fits comfortably in one frame.
 */
object MessageFraming {
    /** Upper bound on a declared frame length, so a corrupt or hostile prefix can't make [readFrame]
     * allocate gigabytes. */
    private const val MAX_FRAME_BYTES = 64 * 1024 * 1024

    /** Writes [payload] as one frame and flushes, so the peer's blocking [readFrame] gets it promptly. */
    fun writeFrame(
        output: OutputStream,
        payload: ByteArray,
    ) {
        val data = DataOutputStream(output)
        data.writeInt(payload.size)
        data.write(payload)
        data.flush()
    }

    /** Reads exactly one length-prefixed frame from [input], blocking until it's fully received. */
    fun readFrame(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        val length = data.readInt()
        require(length in 0..MAX_FRAME_BYTES) {
            "frame length $length outside sane bounds (0..$MAX_FRAME_BYTES) -- corrupt stream or hostile peer"
        }
        val payload = ByteArray(length)
        data.readFully(payload)
        return payload
    }
}
