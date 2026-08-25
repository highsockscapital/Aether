package com.highsockscapital.sunshine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AlpineWorkingDirectoryMappingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var workspaceHostDir: File
    private lateinit var rootfsDir: File

    private val workspaceRoot = "/workspace"
    private val homeDirectory = "/root"

    @Before
    fun setUp() {
        workspaceHostDir = temp.newFolder("workspace")
        rootfsDir = temp.newFolder("rootfs")
    }

    private fun map(guestPath: String): File? =
        AlpineRuntime.mapGuestWorkingDirectoryToHostFile(
            guestPath = guestPath,
            workspaceRoot = workspaceRoot,
            homeDirectory = homeDirectory,
            workspaceHostDir = workspaceHostDir,
            rootfsDir = rootfsDir,
        )

    @Test
    fun workspaceRootMapsToWorkspaceHostDir() {
        assertEquals(workspaceHostDir.absolutePath, map("/workspace")!!.absolutePath)
    }

    @Test
    fun nestedWorkspacePathMapsUnderWorkspaceHostDir() {
        assertEquals(
            File(workspaceHostDir, "buildwatcher-artifacts/123").absolutePath,
            map("/workspace/buildwatcher-artifacts/123")!!.absolutePath,
        )
    }

    @Test
    fun blankPathFallsBackToWorkspaceRoot() {
        assertEquals(workspaceHostDir.absolutePath, map("")!!.absolutePath)
    }

    @Test
    fun relativePathsResolveAgainstWorkspaceRoot() {
        assertEquals(
            File(workspaceHostDir, "subdir").absolutePath,
            map("subdir")!!.absolutePath,
        )
    }

    @Test
    fun tildePathsResolveAgainstHomeDirectory() {
        assertEquals(
            File(rootfsDir, "root/work").absolutePath,
            map("~/work")!!.absolutePath,
        )
    }

    @Test
    fun homeDirectoryMapsInsideRootfs() {
        assertEquals(
            File(rootfsDir, "root/.pi/checkpoints").absolutePath,
            map("/root/.pi/checkpoints")!!.absolutePath,
        )
        assertEquals(
            File(rootfsDir, "root").absolutePath,
            map("/root")!!.absolutePath,
        )
    }

    @Test
    fun traversalIsNormalizedBeforeMapping() {
        assertEquals(
            File(workspaceHostDir, "subdir").absolutePath,
            map("/workspace/other/../subdir")!!.absolutePath,
        )
    }

    @Test
    fun traversalCannotEscapeTheWorkspace() {
        assertNull(map("/workspace/../elsewhere"))
    }

    @Test
    fun pathsOutsideOwnedRootsMapToNull() {
        assertNull(map("/etc"))
        assertNull(map("/dev/null"))
        assertNull(map("/usr/local/bin"))
    }
}
