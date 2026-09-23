package app.inkstave.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureSessionWireTest {
    @Test
    fun `SessionStart round-trips exactly`() {
        val message = CaptureSessionMessage.SessionStart(sessionId = "session-1", scoreTitle = "Moonlight Sonata")
        assertEquals(message, CaptureSessionWire.decode(CaptureSessionWire.encode(message)))
    }

    @Test
    fun `Photo round-trips exactly, including its bytes by content`() {
        val message = CaptureSessionMessage.Photo(sessionId = "session-1", sequenceIndex = 3, bytes = byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(message, CaptureSessionWire.decode(CaptureSessionWire.encode(message)))
    }

    @Test
    fun `Photo with empty bytes round-trips, not treated as an error`() {
        val message = CaptureSessionMessage.Photo(sessionId = "session-1", sequenceIndex = 0, bytes = ByteArray(0))
        assertEquals(message, CaptureSessionWire.decode(CaptureSessionWire.encode(message)))
    }

    @Test
    fun `SessionEnd round-trips exactly`() {
        val message = CaptureSessionMessage.SessionEnd(sessionId = "session-1")
        assertEquals(message, CaptureSessionWire.decode(CaptureSessionWire.encode(message)))
    }

    @Test
    fun `two Photo instances with the same content are equal, unlike ByteArray's default reference equality`() {
        val a = CaptureSessionMessage.Photo("s", 0, byteArrayOf(9, 8, 7))
        val b = CaptureSessionMessage.Photo("s", 0, byteArrayOf(9, 8, 7))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
