package app.inkstave.shared.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class MessageFramingTest {
    @Test
    fun `a written frame reads back with the exact same bytes`() {
        val out = ByteArrayOutputStream()
        val payload = "hello sync".encodeToByteArray()
        MessageFraming.writeFrame(out, payload)

        val read = MessageFraming.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertContentEquals(payload, read)
    }

    @Test
    fun `an empty payload round-trips correctly, not treated as an error`() {
        val empty = ByteArray(0)
        val out = ByteArrayOutputStream()
        MessageFraming.writeFrame(out, empty)

        val read = MessageFraming.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertContentEquals(empty, read)
    }

    @Test
    fun `several frames written in sequence read back in the same order, each with its own bytes`() {
        val out = ByteArrayOutputStream()
        val frames = listOf("first".encodeToByteArray(), "second, a bit longer".encodeToByteArray(), "3".encodeToByteArray())
        frames.forEach { MessageFraming.writeFrame(out, it) }

        val input = ByteArrayInputStream(out.toByteArray())
        frames.forEach { expected -> assertContentEquals(expected, MessageFraming.readFrame(input)) }
    }

    @Test
    fun `a length prefix outside the sane ceiling is rejected rather than trusted`() {
        val out = ByteArrayOutputStream()
        val data = java.io.DataOutputStream(out)
        data.writeInt(Int.MAX_VALUE) // absurd length, no matching payload behind it
        assertFailsWith<IllegalArgumentException> {
            MessageFraming.readFrame(ByteArrayInputStream(out.toByteArray()))
        }
    }
}
