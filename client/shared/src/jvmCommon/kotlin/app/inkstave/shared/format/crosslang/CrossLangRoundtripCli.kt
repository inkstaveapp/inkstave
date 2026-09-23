package app.inkstave.shared.format.crosslang

import app.inkstave.shared.format.ManifestJson
import java.io.File
import kotlin.system.exitProcess

/**
 * Command-line entry point for the cross-language `.smpk` format round-trip
 * check (`format/scripts/cross_lang_roundtrip.sh`).
 *
 * Never invoked by app code -- it exists purely to exercise [ManifestJson]
 * from outside the Kotlin test runner, as one half of a two-language
 * integration test (`docs/testing-strategy.md`'s "cross-language format
 * round-trip" requirement -- previously an open gap noted in `ROADMAP.md`'s
 * M0 section). Run via the Gradle tasks `:shared:crossLangManifestWrite` and
 * `:shared:crossLangManifestRead` (see `client/shared/build.gradle.kts`).
 *
 * **Lives in `jvmCommon` (a production source set), not `jvmCommonTest`,
 * despite conceptually being test/CI tooling.** This is deliberate, not an
 * oversight: this KMP module compiles each target's test sources together
 * with that target's own test dependencies as one unit, so if this lived in
 * `desktopTest` (or a source set it depends on) it would need to resolve
 * everything else `desktopTest` depends on too -- including, since M1's
 * UI-level e2e gap was closed, the `compose.uiTest` dependency
 * (`ui/AppUiTest.kt`). That would make this CLI's own build depend on an
 * entirely unrelated dependency ever resolving, needlessly coupling two
 * independent test-gap fixes together. Living in `jvmCommon` means the
 * `crossLangManifestWrite`/`crossLangManifestRead` Gradle tasks only need
 * the desktop target's *main* compilation, which this CLI's own minimal
 * dependencies (just [ManifestJson] and the JDK) are already part of.
 *
 * Two environment variables control it, rather than more CLI args, so the
 * calling Gradle `JavaExec` task configuration stays simple:
 * - `CROSS_LANG_FIXTURE_PATH`: the canonical known-good manifest.json
 *   (`format/fixtures/manifest.v1.cross-lang.json`) this check verifies
 *   against. Both this CLI and the Python equivalent
 *   (`inkstave_format.cross_lang_cli`) treat that one file as the single
 *   source of truth for what "correct" looks like, rather than each
 *   hardcoding its own copy of the expected values (which could drift
 *   between the two languages unnoticed).
 * - `CROSS_LANG_TARGET_PATH`: the file this run writes to (`write` mode)
 *   or reads from (`read` mode) -- the artifact the *other* language's run
 *   produced or will consume.
 *
 * `write` mode decodes the fixture with [ManifestJson] and re-encodes it to
 * [CROSS_LANG_TARGET_PATH]. `read` mode decodes both the fixture and
 * [CROSS_LANG_TARGET_PATH] with [ManifestJson] and asserts they're equal --
 * structural `data class` equality, which includes `Manifest.unknownFields`,
 * so a mismatch there (the other language failing to preserve the fixture's
 * deliberately-unrecognized `omrPreview` field) fails this check too, not
 * just a mismatch in a known field. Exits non-zero with a descriptive
 * message on any mismatch or usage error.
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
