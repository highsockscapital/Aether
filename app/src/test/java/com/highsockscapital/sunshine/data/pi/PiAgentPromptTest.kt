package com.highsockscapital.sunshine.data.pi

import com.highsockscapital.sunshine.data.AppSettings
import com.highsockscapital.sunshine.data.LocalRuntimeId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiAgentPromptTest {
    @Test
    fun instructionsOnlyAppendSunshineRuntimeConstraints() {
        val instructions = buildPiAgentInstructions(
            settings = AppSettings(),
            workspaceDirectory = "/workspace",
            runtimeId = LocalRuntimeId.Termux,
            agentModeEnabled = false,
        )

        assertTrue(instructions.contains("current local runtime is termux"))
        assertTrue(instructions.contains("use read on the provided path"))
        assertFalse(instructions.contains("analyze_image"))
        assertFalse(instructions.contains("fetch_web_url"))
        assertFalse(instructions.contains("mcp_"))
        assertFalse(instructions.contains("<active_skill"))
    }

    @Test
    fun chromeInstructionsAreOnlyAddedWhenSelected() {
        val disabledInstructions = buildPiAgentInstructions(
            settings = AppSettings(),
            workspaceDirectory = "/workspace",
            runtimeId = LocalRuntimeId.Termux,
            agentModeEnabled = false,
        )
        val enabledInstructions = buildPiAgentInstructions(
            settings = AppSettings(),
            workspaceDirectory = "/workspace",
            runtimeId = LocalRuntimeId.Termux,
            agentModeEnabled = false,
            chromeEnabled = true,
        )

        assertFalse(disabledInstructions.contains("Chrome Extension tool"))
        assertTrue(enabledInstructions.contains("Chrome Extension tool"))
    }
}
