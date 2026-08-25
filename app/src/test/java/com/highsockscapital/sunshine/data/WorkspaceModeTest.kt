package com.highsockscapital.sunshine.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceModeTest {
    @Test
    fun defaultWorkspaceModeIsShared() {
        assertEquals(AgentWorkspaceMode.Shared, AppSettings().agentWorkspaceMode)
        assertEquals(AgentWorkspaceMode.Shared, AgentWorkspaceMode.fromStorage(null))
        assertEquals(AgentWorkspaceMode.Shared, AgentWorkspaceMode.fromStorage("unknown"))
    }

    @Test
    fun workspaceModeParsesStoredValues() {
        assertEquals(
            AgentWorkspaceMode.Shared,
            AgentWorkspaceMode.fromStorage(AgentWorkspaceMode.Shared.storageValue),
        )
        assertEquals(
            AgentWorkspaceMode.PerSession,
            AgentWorkspaceMode.fromStorage(AgentWorkspaceMode.PerSession.storageValue),
        )
    }
}
