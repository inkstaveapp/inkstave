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
 * Wire model for one `POST /process-page` result; mirrors
 * `inkstave_processing.models.ProcessPageResponse` field for field (camelCase keys). Callers convert
 * it to `app.inkstave.shared.importer.ProcessedPage` before writing a `.smpk`.
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
 * Any failed `/process-page` call: a non-2xx response (422 means no page was detected in the
 * photo), a connection failure, or an unparseable response. Callers fall back to importing the raw
 * photo rather than failing the whole capture session.
 */
class ProcessingServiceException(
    message: String,
) : IOException(message)

/**
 * Desktop-only HTTP client for the local `processing-service`, using the JDK's own
 * `java.net.http.HttpClient` (a loopback call doesn't justify an HTTP library). The service is
 * loopback-only by design, hence the fixed default [baseUri].
 */
class ProcessingServiceClient(
    private val baseUri: URI = URI.create("http://127.0.0.1:8787"),
) {
    private val httpClient =
        HttpClient
            .newBuilder()
            // HTTP/1.1 only: the JDK's default sends an `Upgrade: h2c` request that uvicorn
            // doesn't handle alongside a request body, so POST bodies arrived empty.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(HEALTH_CHECK_TIMEOUT_MILLIS))
            .build()

    /** `true` iff `GET /health` answers 200 within a short timeout. Never throws; any failure means "not healthy". */
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
     * Runs the cleanup/OCR pipeline on [imageBytes] via `POST /process-page`, sending the raw bytes
     * as the body. [sessionId] and [sequenceIndex] are part of the API contract but not yet used by
     * the service.
     *
     * @throws ProcessingServiceException on any failure; the caller decides the fallback.
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
