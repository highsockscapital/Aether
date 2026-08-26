package com.highsockscapital.sunshine.data

import android.content.Context
import android.net.Uri
import com.highsockscapital.sunshine.termux.TermuxContract
import java.net.URLDecoder

private const val RuntimeWorkspaceInlineBytesLimit = 5 * 1024 * 1024

/**
 * Termux-only workspace file bridge. All workspace files live in the Termux
 * home directory and are accessed through [WorkspaceFileBridge].
 */
class RuntimeWorkspaceFileBridge(
    context: Context,
    private val termuxFileBridge: WorkspaceFileBridge,
) {
    suspend fun importAttachmentToWorkspace(
        settings: AppSettings,
        sourceUri: Uri,
        sessionId: String,
        attachmentId: String,
        displayName: String,
        mode: AgentWorkspaceMode = AgentWorkspaceMode.Shared,
        onProgress: (WorkspaceImportProgress) -> Unit = {},
    ): Result<ImportedWorkspaceFile> = termuxFileBridge.importAttachmentToWorkspace(
        sourceUri = sourceUri,
        sessionId = sessionId,
        attachmentId = attachmentId,
        displayName = displayName,
        mode = mode,
        onProgress = onProgress,
    )

    suspend fun readWorkspaceFile(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        path: String,
        workingDirectory: String = "",
        byteLimit: Int,
    ): Result<WorkspaceFilePayload> = termuxFileBridge.readWorkspaceFile(
        path = normalizeRuntimeWorkspacePath(path),
        workingDirectory = workingDirectory.ifBlank { termuxWorkspaceDirectory },
        byteLimit = byteLimit,
    )

    suspend fun readWorkspaceFile(
        path: String,
        workingDirectory: String,
        defaultRuntimeId: LocalRuntimeId,
        byteLimit: Int,
    ): Result<WorkspaceFilePayload> = termuxFileBridge.readWorkspaceFile(
        path = normalizeRuntimeWorkspacePath(path),
        workingDirectory = workingDirectory.ifBlank { TermuxContract.HomeDirectory },
        byteLimit = byteLimit,
    )

    suspend fun writeWorkspaceBytes(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        absolutePath: String,
        bytes: ByteArray,
    ): Result<Long> = termuxFileBridge.writeWorkspaceBytes(
        absolutePath = normalizeRuntimeWorkspacePath(absolutePath),
        bytes = bytes,
    )

    suspend fun saveWorkspaceFileToDocument(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        path: String,
        destinationUri: Uri,
        byteLimit: Int = 256 * 1024 * 1024,
    ): Boolean = termuxFileBridge.saveWorkspaceFileToDocument(
        path = termuxFileBridge.resolveTermuxPath(
            path = normalizeRuntimeWorkspacePath(path),
            workingDirectory = termuxWorkspaceDirectory,
        ),
        destinationUri = destinationUri,
        byteLimit = byteLimit,
    )

    fun guessMimeType(path: String): String = termuxFileBridge.guessMimeType(path)
}

internal fun resolveWorkspaceRuntimeId(
    path: String,
    workingDirectory: String,
    defaultRuntimeId: LocalRuntimeId,
): LocalRuntimeId = defaultRuntimeId

internal fun normalizeRuntimeWorkspacePath(path: String): String {
    val trimmed = path.trim()
    if (!trimmed.startsWith("file://", ignoreCase = true)) return trimmed
    val withoutScheme = trimmed.substring("file://".length)
    val withoutLocalhost = when {
        withoutScheme.startsWith("localhost/") -> "/" + withoutScheme.removePrefix("localhost/")
        withoutScheme == "localhost" -> "/"
        else -> withoutScheme
    }
    return URLDecoder.decode(withoutLocalhost, Charsets.UTF_8.name()).trim()
}
