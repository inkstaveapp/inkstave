package app.inkstave.shared.processing

import java.io.File
import java.io.IOException

/**
 * Best-effort launcher for `processing-service`, so nobody has to start it by hand.
 *
 * **Works only in a source checkout.** It looks for `processing-service/.venv` near the current
 * working directory. Packaged builds will have to bundle the service and know where it is; that
 * isn't solved here.
 */
object ProcessingServiceLauncher {
    /**
     * Starts the service unless [client] already reaches one, then waits up to [pollTimeoutMillis]
     * for it to answer. Runs on a daemon thread and never throws: a missing service must never block
     * or crash startup, it only means capture sessions are imported unprocessed.
     */
    fun ensureRunningInBackground(
        client: ProcessingServiceClient,
        workingDirectory: File = File(System.getProperty("user.dir")),
        pollTimeoutMillis: Long = DEFAULT_POLL_TIMEOUT_MILLIS,
    ) {
        Thread {
            runCatching { ensureRunning(client, workingDirectory, pollTimeoutMillis) }
                .onFailure { e -> System.err.println("inkstave: processing-service launch attempt failed: ${e.message}") }
        }.apply {
            isDaemon = true
            name = "inkstave-processing-service-launcher"
            start()
        }
    }

    private fun ensureRunning(
        client: ProcessingServiceClient,
        workingDirectory: File,
        pollTimeoutMillis: Long,
    ) {
        if (client.isHealthy()) return

        val serviceDirectory = findServiceDirectory(workingDirectory)
        if (serviceDirectory == null) {
            System.err.println(
                "inkstave: processing-service not found near $workingDirectory (looked for a sibling/ancestor " +
                    "processing-service/.venv) -- capture sessions will import raw, unprocessed pages until it's " +
                    "reachable at http://127.0.0.1:8787",
            )
            return
        }

        val pythonExecutable = File(serviceDirectory, ".venv/bin/python")
        try {
            // Log to a file, not Redirect.INHERIT: a child holding the parent's stdout/stderr open
            // keeps Gradle waiting on a finished test worker forever. The shutdown hook stops the
            // service with the JVM that started it.
            val logFile = File(System.getProperty("java.io.tmpdir"), LOG_FILE_NAME)
            val process =
                ProcessBuilder(pythonExecutable.absolutePath, "-m", "inkstave_processing.server")
                    .directory(serviceDirectory)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                    .start()
            Runtime.getRuntime().addShutdownHook(Thread { process.destroy() })
            System.err.println("inkstave: started processing-service (log: $logFile)")
        } catch (e: IOException) {
            System.err.println("inkstave: failed to launch processing-service from $serviceDirectory: ${e.message}")
            return
        }

        pollUntilHealthyOrTimeout(client, pollTimeoutMillis)
    }

    /** Looks for `processing-service/.venv` at [workingDirectory] and up to three levels above it, covering the
     * repo root and module directories Gradle may run from. */
    private fun findServiceDirectory(workingDirectory: File): File? =
        listOf(
            File(workingDirectory, "processing-service"),
            File(workingDirectory, "../processing-service"),
            File(workingDirectory, "../../processing-service"),
            File(workingDirectory, "../../../processing-service"),
        ).map { it.canonicalFile }
            .firstOrNull { File(it, ".venv/bin/python").isFile }

    private fun pollUntilHealthyOrTimeout(
        client: ProcessingServiceClient,
        timeoutMillis: Long,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (client.isHealthy()) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        System.err.println(
            "inkstave: launched processing-service but it never became healthy within ${timeoutMillis}ms -- " +
                "capture sessions will import raw, unprocessed pages until it's reachable",
        )
    }

    private const val LOG_FILE_NAME = "inkstave-processing-service.log"
    private const val DEFAULT_POLL_TIMEOUT_MILLIS = 15_000L
    private const val POLL_INTERVAL_MILLIS = 500L
}
