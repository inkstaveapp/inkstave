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
 * Proves [ProcessingServiceClient] actually talks to a real, running `processing-service` over
 * real HTTP -- the one part of the whole M4 arc that had never been exercised Kotlin-calling
 * -real-Python before this pass (every prior `processing-service` test drove it from Python via
 * `TestClient`; every prior client-side sync test proved Kotlin-to-Kotlin over a real socket, but
 * never Kotlin-to-Python). [ProcessingServiceLauncher] (the same production code
 * [app.inkstave.desktop.Main]'s `main()` calls, not a separate test-only launcher) finds and
 * starts the real service from this repo's own `processing-service/` checkout -- if that's not
 * set up in whatever environment runs this test (its `.venv`, per `processing-service/README.md`'s
 * "Setup"), every test here fails loudly with a clear "processing-service never became healthy"
 * message rather than silently skipping, which is the honest state of things: this really does
 * require the real service to be set up to prove what it proves.
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
        // ProcessingServiceLauncher intentionally doesn't hand back a Process reference (it's
        // fire-and-forget by design -- see its own doc), so this test can't precisely target-kill
        // only the instance it started. Harmless for this test's own purposes (the process outlives
        // this JVM either way, same as a manually-started `processing-service` would), and every
        // other desktop test that doesn't need it running is unaffected by it still being up.
    }

    /** A synthetic "photo": a light rectangular "page" inset on a darker background, with a
     * visible border and a couple of rule lines -- enough contrast/structure for the real
     * pipeline's contour-based page detection (`pipeline/geometry.py`) to actually find a page
     * boundary, unlike a single flat color (which has no edges at all to detect). Same spirit as
     * `processing-service/tests/pipeline/fixtures.py`'s own synthetic fixtures, reimplemented
     * here in Kotlin since this test can't import Python test fixtures directly. */
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

    /** A genuinely undetectable "photo": one flat color, no edges anywhere for contour detection
     * to find -- the real negative case `pipeline/geometry.py`'s `_MIN_CONTOUR_AREA_FRACTION`
     * check (via `PageDetectionFailed`) exists for, not a fabricated error condition. */
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
        // A real PNG, not just non-empty bytes -- ImageIO.read returning non-null proves this
        // client's base64 decoding round-tripped the real pipeline's real PNG-encoded output.
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
