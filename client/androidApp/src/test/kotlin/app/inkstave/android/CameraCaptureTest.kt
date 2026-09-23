package app.inkstave.android

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [readCapturedFiles] against real temporary files holding synthetic byte content -- the same
 * honest-fixture approach `ImportPipelineTest`/`LibraryImporterEndToEndTest` use for M1's image
 * import, since there's no real captured camera photo to test against either (no camera in this
 * environment, and this function doesn't touch CameraX/Android framework types at all regardless).
 */
class CameraCaptureTest {
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("inkstave-capture-test-").toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun tempFileWithBytes(
        name: String,
        bytes: ByteArray,
    ): File = File(directory, name).apply { writeBytes(bytes) }

    @Test
    fun `reads each file's bytes and numbers them in the given order`() {
        val first = tempFileWithBytes("inkstave-capture-1.jpg", byteArrayOf(1, 2, 3))
        val second = tempFileWithBytes("inkstave-capture-2.jpg", byteArrayOf(4, 5))

        val picked = readCapturedFiles(listOf(first, second))

        assertEquals(2, picked.size)
        assertEquals("capture-1.jpg", picked[0].displayName)
        assertTrue(picked[0].bytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("capture-2.jpg", picked[1].displayName)
        assertTrue(picked[1].bytes.contentEquals(byteArrayOf(4, 5)))
    }

    @Test
    fun `deletes each file once its bytes are read`() {
        val file = tempFileWithBytes("inkstave-capture-1.jpg", byteArrayOf(9))

        readCapturedFiles(listOf(file))

        assertFalse(file.exists(), "a read capture file should be cleaned up, not left in the cache directory")
    }

    @Test
    fun `empty input produces empty output`() {
        assertEquals(emptyList(), readCapturedFiles(emptyList()))
    }
}
