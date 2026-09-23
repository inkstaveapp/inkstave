package app.inkstave.shared.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The wire framing every sync message (the pairing identity exchange, `PairingSession`; the
 * capture-session transfer, `CaptureSessionMessage`) uses over a TLS socket's raw streams: a
 * 4-byte big-endian length prefix (the payload's byte count, not including this header),
 * followed by exactly that many payload bytes. Deliberately this simple -- no message-type byte
 * at *this* layer (each protocol built on top of it defines its own payload shape inside the
 * length-prefixed body; `CaptureSessionMessage.encode`/`decode` is where a type byte actually
 * lives), no compression, no chunking: TLS already provides integrity/confidentiality, and a
 * capture-session photo is a single phone photo (single-digit megabytes at most), not large
 * enough to need a streamed/chunked transfer within one frame.
 */
object MessageFraming {
    /**
     * A sanity ceiling on a frame's declared length, generously above any real payload this
     * protocol ever sends -- guards [readFrame] against allocating gigabytes of memory for a
     * corrupt or hostile length prefix, not a real expected-size limit.
     */
    private const val MAX_FRAME_BYTES = 64 * 1024 * 1024

    /** Writes [payload] to [output] as one length-prefixed frame, flushing afterward so the peer's blocking
     * [readFrame] call actually receives it promptly rather than sitting in a buffer. */
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
