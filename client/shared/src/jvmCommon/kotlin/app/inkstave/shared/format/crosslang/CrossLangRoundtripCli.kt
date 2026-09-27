package app.inkstave.shared.format.crosslang

import app.inkstave.shared.format.ManifestJson
import java.io.File
import kotlin.system.exitProcess

/**
 * CLI for the cross-language `.smpk` round-trip check (`format/scripts/cross_lang_roundtrip.sh`),
 * run by the Gradle tasks `crossLangManifestWrite`/`crossLangManifestRead`; never used by the app.
 * It lives in `jvmCommon`, not a test source set, so those tasks need only the main classpath.
 *
 * `CROSS_LANG_FIXTURE_PATH` is the shared known-good manifest both languages check against;
 * `CROSS_LANG_TARGET_PATH` is the file this run writes (`write`) or reads (`read`). `read` compares
 * with data-class equality, so unknown fields the other language failed to preserve also fail it.
 * Exits non-zero on a mismatch or usage error.
 */
fun main(args: Array<String>) {
    val mode = args.getOrNull(0)
    val fixturePath = requireEnv("CROSS_LANG_FIXTURE_PATH")
    val targetPath = requireEnv("CROSS_LANG_TARGET_PATH")

    when (mode) {
        "write" -> {
            val fixture = ManifestJson.decode(File(fixturePath).readText())
            File(targetPath).parentFile?.mkdirs()
            File(targetPath).writeText(ManifestJson.encode(fixture))
            println("[kotlin] wrote $targetPath from fixture $fixturePath")
        }

        "read" -> {
            val expected = ManifestJson.decode(File(fixturePath).readText())
            val actual = ManifestJson.decode(File(targetPath).readText())
            if (expected == actual) {
                println("[kotlin] OK: $targetPath matches fixture $fixturePath (including unknown fields)")
            } else {
                System.err.println("[kotlin] MISMATCH between $targetPath and fixture $fixturePath")
                System.err.println("  expected: $expected")
                System.err.println("  actual:   $actual")
                exitProcess(1)
            }
        }

        else -> {
            System.err.println(
                "usage: <write|read> (CROSS_LANG_FIXTURE_PATH and CROSS_LANG_TARGET_PATH env vars required)",
            )
            exitProcess(2)
        }
    }
}

/** Reads [name] from the environment, or exits with status 2 and a message explaining why. */
private fun requireEnv(name: String): String =
    System.getenv(name) ?: run {
        System.err.println("missing required env var $name")
        exitProcess(2)
    }
