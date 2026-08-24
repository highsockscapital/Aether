package com.zhousl.aether.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubagentManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `parseModelId reads model from frontmatter`() {
        val markdown = "---\nname: watcher\nmodel: openrouter/anthropic/claude-sonnet-4.5\n---\n\nBody"
        assertEquals("openrouter/anthropic/claude-sonnet-4.5", AgentFrontmatter.parseModelId(markdown))
    }

    @Test
    fun `parseModelId returns empty when no frontmatter or model`() {
        assertEquals("", AgentFrontmatter.parseModelId("no frontmatter here"))
        assertEquals("", AgentFrontmatter.parseModelId("---\nname: x\n---\nbody"))
    }

    @Test
    fun `withModelId adds updates and removes model line`() {
        val base = "---\nname: watcher\n---\n\nBody"
        val added = AgentFrontmatter.withModelId(base, "openrouter/m1")
        assertEquals("openrouter/m1", AgentFrontmatter.parseModelId(added))
        assertTrue(added.contains("name: watcher"))

        val updated = AgentFrontmatter.withModelId(added, "openrouter/m2")
        assertEquals("openrouter/m2", AgentFrontmatter.parseModelId(updated))

        val removed = AgentFrontmatter.withModelId(updated, "")
        assertFalse(removed.contains("model:"))
        assertTrue(removed.contains("Body"))

        // No frontmatter: one is created.
        val created = AgentFrontmatter.withModelId("Just body", "openrouter/m3")
        assertEquals("openrouter/m3", AgentFrontmatter.parseModelId(created))
    }

    @Test
    fun `ownershipMarker detects built-in files only`() {
        val markdown = BuiltInSubagents.buildWatcher.markdown
        assertEquals("build-watcher", AgentFrontmatter.ownershipMarker(markdown))
        assertNull(AgentFrontmatter.ownershipMarker("# Custom agent\nsome text"))
    }

    @Test
    fun `syncBuiltIns writes enabled and removes disabled built-ins`() {
        val home = tmp.newFolder("home")
        val manager = SubagentManager(home.absolutePath)

        manager.syncBuiltIns(emptyMap())
        val written = File(home, ".pi/agent/agents/build-watcher.md")
        assertTrue(written.exists())
        assertEquals(
            "build-watcher",
            AgentFrontmatter.ownershipMarker(written.readText()),
        )

        manager.syncBuiltIns(mapOf("build-watcher" to SubagentConfig(enabled = false)))
        assertFalse(written.exists())

        manager.syncBuiltIns(
            mapOf("build-watcher" to SubagentConfig(enabled = true, modelId = "openrouter/m1")),
        )
        assertTrue(written.exists())
        assertEquals("openrouter/m1", AgentFrontmatter.parseModelId(written.readText()))
    }

    @Test
    fun `syncBuiltIns never deletes user-authored files`() {
        val home = tmp.newFolder("home")
        val dir = SubagentManager(home.absolutePath).globalAgentsDirectory().apply { mkdirs() }
        val custom = File(dir, "custom-agent.md").apply { writeText("# mine") }

        SubagentManager(home.absolutePath).syncBuiltIns(
            mapOf("custom-agent" to SubagentConfig(enabled = false)),
        )
        assertTrue(custom.exists())
    }

    @Test
    fun `listAgents reports built-in flag and model ids`() {
        val home = tmp.newFolder("home")
        val manager = SubagentManager(home.absolutePath)
        manager.syncBuiltIns(mapOf("build-watcher" to SubagentConfig(modelId = "openrouter/m1")))

        val agents = manager.listAgents(null)
        assertEquals(1, agents.size)
        assertEquals("build-watcher", agents.first().name)
        assertTrue(agents.first().isBuiltIn)
        assertEquals("openrouter/m1", agents.first().modelId)
    }
}
