package com.highsockscapital.sunshine.runtime

import com.highsockscapital.sunshine.data.AppSettings
import com.highsockscapital.sunshine.data.LocalRuntimeId
import org.json.JSONObject

class RuntimeRouter(
    private val termuxRuntime: LocalRuntime,
    private val alpineRuntime: LocalRuntime,
) {
    fun runtimeFor(
        settings: AppSettings,
        environment: String?,
    ): LocalRuntime? {
        val requested = environment?.trim().orEmpty().lowercase()
        val runtimeId = when (requested) {
            "", "default" -> settings.defaultRuntimeId
                ?: chooseOnlyEnabled(settings)
                ?: legacyDefault(settings)
            "termux" -> LocalRuntimeId.Termux
            "alpine" -> LocalRuntimeId.Alpine
            else -> null
        } ?: return null

        if (requested != "termux" && requested != "alpine" && settings.enabledRuntimeIds.isNotEmpty()) {
            if (runtimeId !in settings.enabledRuntimeIds) return null
        }
        return runtimeById(runtimeId)
    }

    fun runtimeById(runtimeId: LocalRuntimeId): LocalRuntime = when (runtimeId) {
        LocalRuntimeId.Termux -> termuxRuntime
        LocalRuntimeId.Alpine -> alpineRuntime
    }

    fun runtimeForRunId(runId: String): Pair<LocalRuntime, String>? {
        val separatorIndex = runId.indexOf(':')
        if (separatorIndex <= 0) return null
        val runtimeId = LocalRuntimeId.fromStorage(runId.substring(0, separatorIndex)) ?: return null
        return runtimeById(runtimeId) to runId.substring(separatorIndex + 1)
    }

    fun runtimeWorkspaceDirectory(
        settings: AppSettings,
        termuxWorkspaceDirectory: String,
        environment: String? = null,
    ): String {
        val runtime = runtimeFor(settings, environment)
            ?: runtimeFor(settings, null)
            ?: termuxRuntime
        return when (runtime.id) {
            LocalRuntimeId.Termux -> termuxWorkspaceDirectory
            LocalRuntimeId.Alpine -> runtime.workspaceRoot
        }
    }

    fun setupRequiredError(environment: String? = null): String =
        JSONObject().apply {
            put("ok", false)
            put("errmsg", "No local runtime is configured for this tool call.")
            put("hint", "Configure Alpine or Termux in Settings, or pass environment as 'alpine' or 'termux' after setup.")
            if (!environment.isNullOrBlank()) put("environment", environment)
        }.toString()
    private fun chooseOnlyEnabled(settings: AppSettings): LocalRuntimeId? =
        settings.enabledRuntimeIds.singleOrNull()

    private fun legacyDefault(settings: AppSettings): LocalRuntimeId? = when {
        settings.termuxSetupCompleted -> LocalRuntimeId.Termux
        settings.alpineSetupCompleted -> LocalRuntimeId.Alpine
        else -> null
    }
}

fun JSONObject.runtimeEnvironment(): String =
    optString("environment").trim()
        .ifBlank { optString("runtime").trim() }
