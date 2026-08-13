package dev.stringfit

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the plugin inside a real Gradle build.
 *
 * The fixture is deliberately Android-free: it exercises the plugin's own
 * contract -- task registration, the extension DSL, catalog parsing, unused
 * detection and the report -- without needing an SDK, which keeps the
 * cross-version matrix fast enough to run on every push.
 */
class FunctionalTest {
    private fun fixture(dir: File) {
        File(dir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        File(dir, "build.gradle.kts").writeText(
            """
            plugins { id("io.github.sarimmehdi.stringfit") }

            stringFit {
                resDirs.setFrom(file("res"))
                sourceDirs.setFrom(file("src"))
            }
            """.trimIndent(),
        )
        File(dir, "res/values").mkdirs()
        File(dir, "res/values/strings.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="tight_label">Downloading</string>
                <string name="roomy_label">OK</string>
                <string name="never_used">Nobody references me</string>
                <string name="debug_only" translatable="false">DEBUG</string>
            </resources>
            """.trimIndent(),
        )
        File(dir, "src").mkdirs()
        File(dir, "src/Ui.kt").writeText(
            """
            fun a() = stringResource(R.string.tight_label)
            fun b() = stringResource(R.string.roomy_label)
            """.trimIndent(),
        )
        // Measurements as the harness would have written them.
        File(dir, "build/stringfit/sites").mkdirs()
        File(dir, "build/stringfit/sites/preview.tsv").writeText(
            listOf(
                // name, preview, maxWidth, intrinsic, maxLines, linesNeeded,
                // layoutWidth, widthDp, fontScale, locale, left, right
                "tight_label\tP[a]\t128\t265\t1\t1\t128\t360\t1.0\tde\t0\t128",
                "tight_label\tP[a]\t128\t120\t1\t1\t120\t360\t1.0\t\t0\t120",
                "roomy_label\tP[a]\t600\t60\t1\t1\t60\t360\t1.0\t\t0\t60",
                "roomy_label\tP[b]\t120\t60\t1\t1\t60\t360\t1.0\t\t0\t60",
            ).joinToString("\n"),
        )
    }

    private fun run(dir: File, gradleVersion: String?, vararg args: String) = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .apply { if (gradleVersion != null) withGradleVersion(gradleVersion) }
        .build()

    @Test
    fun `report classifies, finds conflicts and lists unused strings`(@TempDir dir: File) {
        fixture(dir)
        val result = run(dir, null, "stringFitReport")

        assertEquals(TaskOutcome.SUCCESS, result.task(":stringFitReport")?.outcome)
        val report = File(dir, "build/reports/stringfit/report.txt").readText()

        assertTrue("Catalog          3 translatable" in report, "translatable=false excluded")
        assertTrue("CUT OFF in de" in report, "German overflow reported:\n$report")
        assertTrue("tight_label" in report)
        assertTrue("never_used" in report, "unused string surfaced")
        // roomy_label sits in a 600px and a 120px slot: a 5x spread.
        assertTrue("CONFLICT" in report && "roomy_label" in report, "conflict reported")
        assertTrue(File(dir, "build/reports/stringfit/strings.tsv").readText().isNotEmpty())
    }

    @Test
    fun `failOnCutOff turns a measurement into a build failure`(@TempDir dir: File) {
        fixture(dir)
        File(dir, "build.gradle.kts").appendText("\nstringFit { failOnCutOff = true }\n")
        val result =
            GradleRunner.create()
                .withProjectDir(dir)
                .withPluginClasspath()
                .withArguments("stringFitReport")
                .buildAndFail()
        assertTrue("cut off in the source language" in result.output, result.output)
    }

    @Test
    fun `baseline records unused strings and honours existing decisions`(@TempDir dir: File) {
        fixture(dir)
        run(dir, null, "stringFitBaseline")
        val triage = File(dir, "stringfit.yml")
        assertTrue("never_used: keep" in triage.readText(), triage.readText())

        triage.writeText(triage.readText().replace("never_used: keep", "never_used: ignore"))
        run(dir, null, "stringFitBaseline")
        assertTrue("never_used: ignore" in triage.readText(), "a decision must survive re-running")

        val report = run(dir, null, "stringFitReport").output
        assertTrue("UNUSED" !in report, "an ignored string must not be reported:\n$report")
    }

    @Test
    fun `applying to a project with no android modules still registers the tasks`(
        @TempDir dir: File,
    ) {
        fixture(dir)
        val tasks = run(dir, null, "tasks", "--group", "stringfit").output
        listOf("stringFitReport", "stringFitBaseline", "stringFitInstallHarness")
            .forEach { assertTrue(it in tasks, "missing $it in:\n$tasks") }
    }

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("gradleVersions")
    fun `works across supported gradle versions`(version: String, @TempDir dir: File) {
        fixture(dir)
        val result = run(dir, version, "stringFitReport")
        assertEquals(TaskOutcome.SUCCESS, result.task(":stringFitReport")?.outcome)
        assertTrue("CUT OFF in de" in File(dir, "build/reports/stringfit/report.txt").readText())
    }

    companion object {
        /** Overridable so CI can widen the matrix without touching the test. */
        @JvmStatic
        fun gradleVersions(): List<String> = System.getProperty("stringfit.gradleVersions")
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?: listOf(MINIMUM_GRADLE)

        /**
         * Gradle 8.7 and older lack `ConfigurableFileCollection.convention`,
         * which the extension relies on so a user's `resDirs` override wins
         * over the default. Verified by the matrix in CI.
         */
        const val MINIMUM_GRADLE = "8.8"
    }
}
