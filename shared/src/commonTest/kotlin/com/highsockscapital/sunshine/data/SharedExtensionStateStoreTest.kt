package com.highsockscapital.sunshine.data

import com.highsockscapital.sunshine.runtime.MultiplatformLocalRuntime
import com.highsockscapital.sunshine.runtime.RuntimeFileSystem
import com.highsockscapital.sunshine.runtime.RuntimeProcess
import com.highsockscapital.sunshine.runtime.RuntimeProcessSpec
import com.highsockscapital.sunshine.runtime.RuntimeSetupProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SharedExtensionStateStoreTest {
    @Test
    fun defaultsToDisablingPreinstalledExtensionsWhenNotConfigured() = runTest {
        val runtime = ExtensionStateFakeRuntime()
        val store = SharedExtensionStateStore(runtime)
        val initial = store.load()
        assertEquals(
            setOf(
                "/root/.sunshine/extensions/pi-web-access",
                "/root/.sunshine/extensions/pi-mcp-adapter",
                "/root/.sunshine/extensions/pi-subagents",
            ),
            initial.disabledExtensionPaths,
        )
    }

    @Test
    fun persistsPackageAndImportedExtensionEnableState() = runTest {
        val runtime = ExtensionStateFakeRuntime()
        val store = SharedExtensionStateStore(runtime)

        store.setPackageEnabled("npm:sample", false)
        store.setImportedExtensionEnabled("/root/.sunshine/extensions/sample.ts", false)

        val restored = SharedExtensionStateStore(runtime).load()
        assertEquals(setOf("npm:sample"), restored.disabledPackageSources)
        assertEquals(
            setOf(
                "/root/.sunshine/extensions/pi-web-access",
                "/root/.sunshine/extensions/pi-mcp-adapter",
                "/root/.sunshine/extensions/pi-subagents",
                "/root/.sunshine/extensions/sample.ts",
            ),
            restored.disabledExtensionPaths,
        )

        store.setPackageEnabled("npm:sample", true)
        store.setImportedExtensionEnabled("/root/.sunshine/extensions/sample.ts", true)
        store.setImportedExtensionEnabled("/root/.sunshine/extensions/pi-web-access", true)
        store.setImportedExtensionEnabled("/root/.sunshine/extensions/pi-mcp-adapter", true)
        store.setImportedExtensionEnabled("/root/.sunshine/extensions/pi-subagents", true)
        val enabled = store.load()
        assertTrue(enabled.disabledPackageSources.isEmpty())
        assertTrue(enabled.disabledExtensionPaths.isEmpty())
    }
}

private class ExtensionStateFakeRuntime : MultiplatformLocalRuntime {
    override val homeDirectory = "/root"
    override val workspaceRoot = "/workspace"
    private val files = mutableMapOf<String, ByteArray>()

    override val fileSystem: RuntimeFileSystem = object : RuntimeFileSystem {
        override suspend fun exists(path: String): Boolean = path in files
        override suspend fun createDirectories(path: String) = Unit
        override suspend fun read(path: String): ByteArray = files.getValue(path)
        override suspend fun write(path: String, content: ByteArray, executable: Boolean) {
            files[path] = content
        }
        override suspend fun remove(path: String, recursive: Boolean) {
            files.remove(path)
        }
        override suspend fun bindHostDirectory(hostPath: String, guestPath: String, readOnly: Boolean) = Unit
    }

    override suspend fun initialize(onProgress: (RuntimeSetupProgress) -> Unit) = Unit
    override suspend fun startProcess(spec: RuntimeProcessSpec): RuntimeProcess = error("Unexpected process")
}
