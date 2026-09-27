package app.inkstave.shared.processing

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Kotlin-to-Python check: [ProcessingServiceClient] against the real `processing-service`, started by
 * the production [ProcessingServiceLauncher]. Needs `processing-service/.venv` set up; without it every
 * test fails with "never became healthy" rather than skipping.
 */
class ProcessingServiceClientTest {
    private lateinit var client: ProcessingServiceClient

    @BeforeTest
    fun setUp() {
        client = ProcessingServiceClient()
        ProcessingServiceLauncher.ensureRunningInBackground(client)
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline && !client.isHealthy()) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        check(client.isHealthy()) {
            "processing-service never became healthy within ${STARTUP_TIMEOUT_MILLIS}ms -- is processing-service/.venv " +
                "set up (processing-service/README.md's \"Setup\")? Expected to find it near " +
                System.getProperty("user.dir")
        }
    }

    @AfterTest
    fun tearDown() {
        // The launcher returns no Process handle, so the service keeps running for the other tests;
        // it is stopped when this JVM exits.
    }

    /** A synthetic photo: a light bordered page with rule lines on a darker background, giving the
     * pipeline's contour detection an edge to find. */
    private fun samplePagePhotoBytes(): ByteArray {
        val canvasSize = 400
        val margin = 40
        val image = BufferedImage(canvasSize, canvasSize, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(70, 70, 70)
        g.fillRect(0, 0, canvasSize, canvasSize)
        g.color = Color(245, 245, 245)
        g.fillRect(margin, margin, canvasSize - 2 * margin, canvasSize - 2 * margin)
        g.color = Color(20, 20, 20)
        g.drawRect(margin, margin, canvasSize - 2 * margin - 1, canvasSize - 2 * margin - 1)
        g.drawLine(margin + 20, margin + 60, canvasSize - margin - 20, margin + 60)
        g.drawLine(margin + 20, margin + 100, canvasSize - margin - 20, margin + 100)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /** One flat colour with no edges: the real negative case for page detection. */
    private fun blankPhotoBytes(): ByteArray {
        val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 200, 200)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    @Test
    fun `isHealthy is true against a real running service`() {
        assertTrue(client.isHealthy())
    }

    @Test
    fun `isHealthy is false against a port nothing is listening on`() {
        val unreachable = ProcessingServiceClient(java.net.URI.create("http://127.0.0.1:1"))
        assertFalse(unreachable.isHealthy())
    }

    @Test
    fun `processPage against the real service returns a cleaned page with real processing and OCR metadata`() {
        val response = client.processPage(samplePagePhotoBytes(), sessionId = "test-session", sequenceIndex = 0)

        assertTrue(
            response.width > 0 && response.height > 0,
            "processed page must have real dimensions, got ${response.width}x${response.height}",
        )
        assertTrue(response.decodedImageBytes().isNotEmpty(), "cleaned image bytes must be non-empty")
        // A decodable PNG, proving the base64 output round-tripped.
        val decoded = ImageIO.read(java.io.ByteArrayInputStream(response.decodedImageBytes()))
        assertTrue(decoded != null && decoded.width > 0, "decoded cleaned image must be a real, readable PNG")
        assertEquals("coons-boundary-v1", response.processing.dewarpMeshVersion, "real dewarp model version from pipeline/geometry.py")
        assertTrue(response.processing.cropPolygon.size == 4, "a detected page's crop polygon has 4 corners")
        assertTrue(response.ocr.engineVersion.isNotBlank(), "real Tesseract version string, not a placeholder")
    }

    @Test
    fun `processPage against the real service throws for an undetectable page, not a 500`() {
        val exception =
            assertFailsWith<ProcessingServiceException> {
                client.processPage(blankPhotoBytes(), sessionId = "test-session", sequenceIndex = 0)
            }
        assertTrue(
            exception.message?.contains("422") == true,
            "expected the real service's documented 422 (docs/image-pipeline.md: a real, expected outcome for a bad " +
                "photo, not a server error), got: ${exception.message}",
        )
    }

    private companion object {
        const val STARTUP_TIMEOUT_MILLIS = 20_000L
        const val POLL_INTERVAL_MILLIS = 300L
    }
}
