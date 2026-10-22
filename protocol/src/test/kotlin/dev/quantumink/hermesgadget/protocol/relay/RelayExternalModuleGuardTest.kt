package dev.quantumink.hermesgadget.protocol.relay

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Same-module tests see Kotlin `internal`, so they cannot prove the consumer boundary. This guard compiles the
 * probe in `tools/relay-external-probe` as a DIFFERENT Kotlin module against this module's compiled classes with the
 * offline compiler from the owned Gradle cache, and requires the exact reconstruction of a fetched prompt from an
 * event prompt to be refused with an `internal` visibility diagnostic while genuine fetched decoding still works.
 * It is skipped (and reported as skipped) when the owned offline cache or python3 is unavailable.
 */
class RelayExternalModuleGuardTest {
    @Test fun anOutsideKotlinModuleCannotRebuildOrRelabelAPromptButGenuineFetchStillResponds() {
        val gradleHome = System.getenv("GRADLE_USER_HOME")
        assumeTrue("needs the owned offline Gradle cache", gradleHome != null)
        assumeTrue(File(gradleHome, "caches/modules-2/files-2.1/org.jetbrains.kotlin").isDirectory)
        val script = File("tools/relay-external-probe/run_probe.py")
        assumeTrue("probe sources not found from ${File(".").absolutePath}", script.isFile)
        val java = File(System.getProperty("java.home"), "bin/java")
        val classes = File(Prompt::class.java.protectionDomain.codeSource.location.toURI())
        val vectors = File(
            RelayVectors::class.java.getResource("/relay-v1/manifest.json")!!.toURI()
        ).parentFile
        val out = File(Files.createTempDirectory("relay-external-probe").toFile(), "out")
        val process = ProcessBuilder(
            "python3", "-I", script.path, "--jar", classes.path, "--out", out.path,
            "--gradle-home", gradleHome, "--java", java.path, "--vectors", vectors.path,
            "--expect", "new"
        ).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        assertTrue("probe timed out", process.waitFor(10, TimeUnit.MINUTES))
        assertEquals(log, 0, process.exitValue())
        val results = Json.parseToJsonElement(
            File(out, "results.json").readText()
        ).jsonObject["results"]!!.jsonObject
        val verdicts = results.mapValues { it.value.jsonObject["ok"]!!.jsonPrimitive.content }
        assertEquals(
            setOf(
                "positive compiles",
                "positive runs: fetched actionable, event and notice refused",
                "fabrication refused with the internal-visibility diagnostic only",
                "control-actionable refused as internal",
                "control-decode refused as internal"
            ),
            verdicts.keys
        )
        assertTrue(verdicts.toString(), verdicts.values.all { it == "true" })
    }
}
