package com.highsockscapital.sunshine.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.highsockscapital.sunshine.data.pi.PiKernelBridge
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlpineRuntimeInstrumentedTest {
    @Test
    fun alpineRuntimeStartsShellFromAppProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AlpineRuntime(context)

        val setup = runtime.initialize()
        assertEquals(setup.detail, LocalRuntimeIssue.Ready, setup.issue)

        val result = JSONObject(
            runtime.executeCommand(
                command = "echo SUNSHINE_ALPINE_APP_PROCESS_OK; cat /etc/alpine-release; uname -m; pwd",
                workingDirectory = runtime.homeDirectory,
                awaitTimeoutMillis = 30_000L,
            )
        )

        assertTrue(result.optString("errmsg"), result.optBoolean("ok"))
        val stdout = result.optString("stdout")
        assertTrue(stdout, stdout.contains("SUNSHINE_ALPINE_APP_PROCESS_OK"))
        assertTrue(stdout, stdout.contains("aarch64"))
        assertTrue(stdout, stdout.contains("/root"))
    }

    @Test
    fun pythonPackageProfileInstallsAndRuns() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AlpineRuntime(context)

        val setup = runtime.initialize()
        assertEquals(setup.detail, LocalRuntimeIssue.Ready, setup.issue)

        val profile = runtime.installPackageProfile("python")
        assertEquals(profile.detail, LocalRuntimeIssue.Ready, profile.issue)

        val result = JSONObject(
            runtime.executeCommand(
                command = "python3 --version && python3 - <<'PY'\nprint('SUNSHINE_ALPINE_PYTHON_OK')\nPY",
                workingDirectory = runtime.homeDirectory,
                awaitTimeoutMillis = 30_000L,
            )
        )

        assertTrue(result.optString("errmsg"), result.optBoolean("ok"))
        val stdout = result.optString("stdout")
        assertTrue(stdout, stdout.contains("Python"))
        assertTrue(stdout, stdout.contains("SUNSHINE_ALPINE_PYTHON_OK"))
    }

    @Test
    fun piBridgeStartsWithSupportedNodeAndReportsPinnedVersions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = PiKernelBridge(AlpineRuntime(context))

        try {
            val ping = bridge.ping()
            assertEquals("2.0.0-alpha.0", ping.getString("bridge_version"))
            assertEquals("0.83.0", ping.getString("pi_ai_version"))
            assertEquals("0.83.0", ping.getString("pi_agent_core_version"))
            val nodeVersion = ping.getString("node_version").removePrefix("v")
            val major = nodeVersion.substringBefore('.').toInt()
            val minor = nodeVersion.substringAfter('.').substringBefore('.').toInt()
            assertTrue(nodeVersion, major > 22 || major == 22 && minor >= 19)
        } finally {
            bridge.stop()
        }
    }
}
