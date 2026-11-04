package com.musicremover.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Termux install/update scripts install "yt-dlp[default]" and deno, and keep the && chain intact. */
class TermuxScriptsTest {
    private val deno = TermuxHelper.DENO_STEP
    private val apt = TermuxHelper.APT_STEP
    private val torchCpu = TermuxHelper.TORCH_CPU_STEP
    private val install = TermuxHelper.installScript()
    private val update = TermuxHelper.updateScript()

    // Gradle runs unit tests with the module dir (android/app) as the working dir
    private val shellFile = File("../termux-setup.sh")

    private fun shell(): String {
        assertTrue("termux-setup.sh missing at ${shellFile.absolutePath}", shellFile.isFile)
        return shellFile.readText()
    }

    private fun scripts() = mapOf("installScript()" to install, "updateScript()" to update, "termux-setup.sh" to shell())

    @Test
    fun everyScriptContainsDenoStepVerbatim() {
        for ((name, text) in scripts()) assertTrue("$name lacks DENO_STEP", text.contains(deno))
    }

    @Test
    fun everyScriptInstallsYtDlpDefaultWithDoubleQuotes() {
        for ((name, text) in scripts()) assertTrue("$name lacks \"yt-dlp[default]\"", text.contains("\"yt-dlp[default]\""))
    }

    @Test
    fun everyScriptAptInstallsCurlUnzipCaCertificates() {
        val segment = Regex("apt install -y [^&;\\n]*curl unzip ca-certificates")
        for ((name, text) in scripts()) assertTrue("$name: no apt install -y with curl unzip ca-certificates", segment.containsMatchIn(text))
    }

    @Test
    fun denoStepIsAGroupWithoutSingleQuotes() {
        assertTrue(deno.startsWith("{ ("))
        assertTrue(deno.endsWith("; }"))
        assertFalse(deno.contains('\''))
    }

    @Test
    fun aptStepIsAGroupWithoutSingleQuotes() {
        assertTrue(apt.startsWith("{ apt update -y && apt install -y"))
        assertTrue(apt.endsWith("; }"))
        assertFalse(apt.contains('\''))
    }

    @Test
    fun everyScriptInstallsCpuTorchBeforeAudioSeparator() {
        for ((name, text) in scripts()) {
            val torch = text.indexOf(torchCpu)
            assertTrue("$name lacks TORCH_CPU_STEP", torch >= 0)
            assertTrue("$name: TORCH_CPU_STEP must precede \"audio-separator[cpu]\"", torch < text.indexOf("\"audio-separator[cpu]\""))
        }
    }

    @Test
    fun torchCpuStepHasNoSingleQuotes() {
        assertFalse(torchCpu.contains('\''))
    }

    @Test
    fun noScriptInstallsFfmpegFromPip() {
        for ((name, text) in scripts()) {
            assertFalse("$name installs ffmpeg with pip", text.contains("pip install ffmpeg"))
            assertFalse("$name installs ffmpeg-python", text.contains("ffmpeg-python"))
        }
    }

    @Test
    fun installScriptOrder() {
        assertOrdered("installScript()", install, listOf("curl", torchCpu, "pip install fastapi", deno, "git clone", "✅"))
    }

    @Test
    fun updateScriptOrder() {
        assertOrdered("updateScript()", update, listOf("git reset", "apt update", "apt install", torchCpu, "pip install --upgrade", deno, "✅"))
    }

    @Test
    fun builderOutputsHaveBalancedSingleQuotes() {
        assertEquals(0, install.count { it == '\'' } % 2)
        assertEquals(0, update.count { it == '\'' } % 2)
    }

    private fun assertOrdered(name: String, text: String, parts: List<String>) {
        val positions = parts.map { part ->
            text.indexOf(part).also { assertTrue("$name lacks \"$part\"", it >= 0) }
        }
        for (i in 1 until positions.size) {
            assertTrue(
                "$name: \"${parts[i - 1].take(20)}\" (${positions[i - 1]}) must precede \"${parts[i].take(20)}\" (${positions[i]})",
                positions[i - 1] < positions[i],
            )
        }
    }
}
