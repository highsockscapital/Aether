package com.highsockscapital.sunshine.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SunshineToolExecutorTest {
    @Test
    fun hostToolDefinitionsDoNotDuplicatePiNativeTools() {
        val definitions = SunshineToolExecutor.hostToolDefinitions()
        assertEquals(0, definitions.length())
    }

    @Test
    fun sanitizeAgentDisplayOutputRemovesScreenshotBytes() {
        val sanitized = SunshineToolExecutor.sanitizeToolOutputForConversation(
            toolName = "agent_display",
            output = JSONObject().apply {
                put("ok", true)
                put("screenshot_base64", "abc123")
                put("screenshot_mime_type", "image/png")
            }.toString(),
        )

        val json = JSONObject(sanitized)
        assertFalse(json.has("screenshot_base64"))
        assertTrue(json.getBoolean("screenshot_injected_into_next_model_request"))
        assertEquals("image/png", json.getString("screenshot_mime_type"))
    }

    @Test
    fun dynamicHostToolDefinitionsExcludeMcpAndChromeButIncludeAgentMode() {
        val definitions = SunshineToolExecutor.hostToolDefinitions(
            selfManagementTool = null,
            agentModeEnabled = true,
        )
        val names = (0 until definitions.length())
            .map { definitions.getJSONObject(it).getString("name") }

        assertFalse("mcp_list_tools" in names)
        assertFalse("mcp__docs__search" in names)
        assertTrue("agent_display" in names)
        assertFalse("chrome" in names)
        assertEquals(
            "sequential",
            (0 until definitions.length())
                .map { definitions.getJSONObject(it) }
                .first { it.getString("name") == "agent_display" }
                .getString("execution_mode"),
        )
    }

    @Test
    fun inferToolOutputOkHonorsSunshineJsonFlags() {
        assertTrue(SunshineToolExecutor.inferToolOutputOk("""{"ok":true}"""))
        assertFalse(SunshineToolExecutor.inferToolOutputOk("""{"ok":false}"""))
        assertFalse(SunshineToolExecutor.inferToolOutputOk("""{"err":true}"""))
        assertTrue(SunshineToolExecutor.inferToolOutputOk("plain text"))
    }

    @Test
    fun workspaceFileRoutingRecognizesAlpineAndTermuxRoots() {
        assertEquals(
            LocalRuntimeId.Alpine,
            resolveWorkspaceRuntimeId(
                path = "/workspace/agent-mode/capture.png",
                workingDirectory = "",
                defaultRuntimeId = LocalRuntimeId.Termux,
            ),
        )
        assertEquals(
            LocalRuntimeId.Termux,
            resolveWorkspaceRuntimeId(
                path = "/data/data/com.termux/files/home/.sunshine/workspace/uploads/image.png",
                workingDirectory = "",
                defaultRuntimeId = LocalRuntimeId.Alpine,
            ),
        )
        assertEquals(
            LocalRuntimeId.Alpine,
            resolveWorkspaceRuntimeId(
                path = "relative.png",
                workingDirectory = "/workspace",
                defaultRuntimeId = LocalRuntimeId.Termux,
            ),
        )
        assertEquals(
            LocalRuntimeId.Termux,
            resolveWorkspaceRuntimeId(
                path = "/data/data/com.termux/files/home/.sunshine/workspace/output.png",
                workingDirectory = "/workspace",
                defaultRuntimeId = LocalRuntimeId.Alpine,
            ),
        )
        assertEquals(
            LocalRuntimeId.Alpine,
            resolveWorkspaceRuntimeId(
                path = "file:///workspace/agent-mode/capture.png",
                workingDirectory = "/data/data/com.termux/files/home/.sunshine/workspace",
                defaultRuntimeId = LocalRuntimeId.Termux,
            ),
        )
    }

}
