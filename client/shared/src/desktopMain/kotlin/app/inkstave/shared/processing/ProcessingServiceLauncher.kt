package app.inkstave.shared.processing

import java.io.File
import java.io.IOException

/**
 * Best-effort launcher for `processing-service`, so a user doesn't have to manually start it in a
 * separate terminal before capture-session photos can be processed (`ROADMAP.md`'s M4 desktop
 * -pipeline-integration entry).
 *
 * **This is a dev-checkout-only mechanism, not a real release-packaging story.** It looks for a
 * `processing-service/` directory with its own `.venv` already set up (per
 * `processing-service/README.md`'s "Setup") at a handful of paths relative to wherever this
 * process's current working directory happens to be -- which is exactly the layout a `git clone`
 * of this repo plus `./gradlew :desktopApp:run` produces, and nothing else. A packaged release
 * build (`ROADMAP.md` M7: Flatpak/AppImage) will need to actually bundle `processing-service` (or
 * a compiled equivalent of it) and know unambiguously where it lives, which this class deliberately
 * does not attempt to solve -- that is real, separate, not-yet-designed work for M7, not something
 * to paper over here with a guess that would quietly stop working outside a source checkout.
 */
object ProcessingServiceLauncher {
    /**
     * Fire-and-forget: checks [client] first (the service might already be running -- e.g.
     * started manually, or by an earlier launch of this same app), and if not, tries to locate and
     * launch it, then polls [client] until it responds or [pollTimeoutMillis] elapses. Runs
     * entirely on a background daemon thread and never throws -- this must never block or crash
     * app startup, since a missing/unreachable `processing-service` is a real, expected outcome
     * (see this class's own doc) that should only ever downgrade capture-session handling to raw
     * import ([app.inkstave.shared.sync.CaptureSessionReceiver]), never break anything else the
     * app does.
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
            // Output goes to a log file, not Redirect.INHERIT: an inherited stdout/stderr is held
            // open by the child, and on CI that kept Gradle waiting on the finished test worker's
            // output forever (every test passed, the job never ended). The shutdown hook stops the
            // service with the JVM that started it, instead of leaving an orphan behind.
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

    /** Every candidate location a `processing-service/` sibling could be, relative to
     * [workingDirectory] -- covers both plausible Gradle `Test`/`run` working directories in this
     * repo's layout (the repo root, or a module directory one or two levels below it) without
     * needing to know exactly which one applies (see this class's own doc on why that's
     * inherently fragile outside a source checkout). */
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
