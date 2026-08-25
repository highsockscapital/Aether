package com.highsockscapital.sunshine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AlpineWorkingDirectoryMappingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val workspaceRoot = "/workspace"
    private val homeDirectory = "/root"

    private fun map(guestPath: String): File? =
        AlpineRuntime.mapGuestWorkingDirectoryToHostFile(
            guestPath = guestPath,
            workspaceRoot = workspaceRoot,
            homeDirectory = homeDirectory,
            workspaceHostDir = temp.newFolder("workspace"),
            rootfsDir = temp.newFolder("rootfs"),
        )

    @Test
    fun workspaceRootMapsToWorkspaceHostDir() {
        val host = temp.root.resolve("workspace")
        assertEquals(host.absolutePath, map("/workspace")!!.absolutePath)
    }

    @Test
    fun nestedWorkspacePathMapsUnderWorkspaceHostDir() {
        val host = temp.root.resolve("workspace")
        assertEquals(
            File(host, "buildwatcher-artifacts/123").absolutePath,
            map("/workspace/buildwatcher-artifacts/123")!!.absolutePath,
        )
    }

    @Test
    fun blankPathFallsBackToWorkspaceRoot() {
        val host = temp.root.resolve("workspace")
        assertEquals(host.absolutePath, map("")!!.absolutePath)
    }

    @Test
    fun relativePathsResolveAgainstWorkspaceRoot() {
        val host = temp.root.resolve("workspace")
        assertEquals(
            File(host, "subdir").absolutePath,
            map("subdir")!!.absolutePath,
        )
    }

    @Test
    fun homeDirectoryMapsInsideRootfs() {
        val rootfs = temp.root.resolve("rootfs")
        assertEquals(
            File(rootfs, "root/.pi/checkpoints").absolutePath,
            map("/root/.pi/checkpoints")!!.absolutePath,
        )
        assertEquals(
            File(rootfs, "root").absolutePath,
            map("/root")!!.absolutePath,
        )
    }

    @Test
    fun traversalIsNormalizedBeforeMapping() {
        val host = temp.root.resolve("workspace")
        assertEquals(
            File(host, "subdir").absolutePath,
            map("/workspace/other/../subdir")!!.absolutePath,
        )
    }

    @Test
    fun pathsOutsideOwnedRootsMapToNull() {
        assertNull(map("/etc"))
        assertNull(map("/dev/null"))
        assertNull(map("/usr/local/bin"))
    }
}
