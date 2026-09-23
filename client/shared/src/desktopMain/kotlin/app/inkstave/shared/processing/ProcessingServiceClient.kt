package app.inkstave.shared.processing

import app.inkstave.shared.format.PageOcr
import app.inkstave.shared.format.PageProcessing
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Base64

/**
 * One page's result from `processing-service`'s `POST /process-page`
 * (`docs/image-pipeline.md`'s "Service interface (sketch)",
 * `processing-service/README.md`'s "The HTTP API"). Mirrors
 * `inkstave_processing.models.ProcessPageResponse` field-for-field, including its camelCase
 * convention (kotlinx.serialization's default property-name-as-JSON-key behaviour already matches
 * it here, the same as `PageProcessing`/`PageOcr` -- no `@SerialName` needed) -- this is a wire
 * -contract model, distinct from `app.inkstave.shared.importer.ProcessedPage` (which is what a
 * caller actually wants to write into a `.smpk`; [cleanedImageBase64] there gets decoded into raw
 * PNG bytes).
 */
@Serializable
data class ProcessPageResponseBody(
    val cleanedImageBase64: String,
    val width: Int,
    val height: Int,
    val aspectRatioClass: String,
    val processing: PageProcessing,
    val ocr: PageOcr,
) {
    /** [cleanedImageBase64], decoded -- the one field a caller can't use as-is off the wire. */
    fun decodedImageBytes(): ByteArray = Base64.getDecoder().decode(cleanedImageBase64)
}

/**
 * Thrown for any `/process-page` call that didn't succeed -- a non-2xx response (most notably 422,
 * "no page could be detected in this photo," per `docs/image-pipeline.md`), a connection failure,
 * or a response `processing-service`'s own contract doesn't actually match. Callers
 * ([app.inkstave.shared.sync.CaptureSessionReceiver]) catch this to fall back to raw,
 * unprocessed import rather than failing a whole capture session over one bad photo or an
 * unreachable service -- see that class's own doc for the fallback policy.
 */
class ProcessingServiceException(
    message: String,
) : IOException(message)

/**
 * Desktop-only HTTP client for `processing-service` (`docs/architecture.md`: "It is not expected
 * to run on Android; the desktop client is its only caller"). Built on the JDK's own
 * `java.net.http.HttpClient` (available since Java 11) deliberately -- this is the one new
 * outbound integration point this pass adds, and it talks to a service already running on the
 * same machine over loopback, which doesn't warrant a new HTTP-client dependency on top of what
 * the JDK already ships.
 *
 * [baseUri] defaults to `processing-service/README.md`'s documented `127.0.0.1:8787` -- the
 * service is loopback-only by design (`docs/image-pipeline.md`), so there is no legitimate reason
 * for this to ever point anywhere else in v1.
 */
class ProcessingServiceClient(
    private val baseUri: URI = URI.create("http://127.0.0.1:8787"),
) {
    private val httpClient =
        HttpClient
            .newBuilder()
            // HTTP_1_1, not the JDK's default HTTP_2-preferred negotiation: this client only ever
            // talks to one specific plain-HTTP/1.1 uvicorn server (never anything HTTP/2-capable),
            // and defaulting to HTTP/2 preference sends an `Upgrade: h2c` cleartext-upgrade attempt
            // on every new connection that uvicorn's H11 implementation logs as "Unsupported
            // upgrade request" and does not handle cleanly alongside a request body -- a real bug
            // this project's own integration tests against the real service caught directly (a
            // POST /process-page body silently arriving empty server-side, not a hypothetical).
            // Pinning HTTP/1.1 here is the fix, not a workaround for something wrong on the server
            // side: there's no reason for a loopback call to a single-process dev service to
            // attempt HTTP/2 negotiation in the first place.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MILLIS))
            .build()

    /**
     * `true` iff `GET /health` responds successfully within a short timeout -- the fast, cheap
     * check [ProcessingServiceLauncher] tries first (the service might already be running, e.g.
     * started manually for development) and [app.inkstave.shared.sync.CaptureSessionReceiver]
     * uses per received session to decide whether to even attempt processing. Never throws --
     * any failure (connection refused, timeout, a non-200 response) is exactly the "not healthy"
     * case, not something worth distinguishing here.
     */
    fun isHealthy(): Boolean =
        try {
            val request =
                HttpRequest
                    .newBuilder(baseUri.resolve("/health"))
                    .timeout(Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MILLIS))
                    .GET()
                    .build()
            httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
        } catch (e: IOException) {
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    /**
     * Runs the real cleanup/OCR pipeline on [imageBytes] via `POST /process-page`
     * (`processing-service/README.md`'s exact query-parameter/body shape: raw bytes as the body,
     * not JSON/multipart-wrapped -- see that endpoint's own doc for why). [sessionId]/
     * [sequenceIndex] are threaded through per the documented contract (not consumed by the
     * pipeline itself yet, per that endpoint's own doc), so a future slice examining server-side
     * logs/metrics per session has them without this call site needing to change.
     *
     * @throws ProcessingServiceException on any failure -- a 422 (no page detected), a connection
     * problem, or a response this client's own model doesn't parse as valid JSON. Callers decide
     * the fallback policy (see that exception's own doc); this method doesn't try to guess one.
     */
    fun processPage(
        imageBytes: ByteArray,
        sessionId: String,
        sequenceIndex: Int,
        contrastStrength: Double = DEFAULT_CONTRAST_STRENGTH,
    ): ProcessPageResponseBody {
        val uri =
            baseUri.resolve(
                "/process-page?session_id=${urlEncode(sessionId)}&sequence_index=$sequenceIndex&contrast_strength=$contrastStrength",
            )
        val request =
            HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofMillis(PROCESS_PAGE_TIMEOUT_MILLIS))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
                .build()
        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: HttpTimeoutException) {
                throw ProcessingServiceException("timed out calling /process-page: ${e.message}")
            } catch (e: IOException) {
                throw ProcessingServiceException("failed calling /process-page: ${e.message}")
            }
        if (response.statusCode() != 200) {
            throw ProcessingServiceException("/process-page returned HTTP ${response.statusCode()}: ${response.body()}")
        }
        return try {
            Json.decodeFromString(ProcessPageResponseBody.serializer(), response.body())
        } catch (e: SerializationException) {
            throw ProcessingServiceException("could not parse /process-page response: ${e.message}")
        }
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        const val HEALTH_CHECK_TIMEOUT_MILLIS = 1_500L
        const val PROCESS_PAGE_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_CONTRAST_STRENGTH = 0.7
    }
}
