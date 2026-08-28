package com.highsockscapital.sunshine.runtime

import com.highsockscapital.sunshine.data.AppSettings
import com.highsockscapital.sunshine.data.LocalRuntimeId
import org.json.JSONObject

class RuntimeRouter(
    private val termuxRuntime: LocalRuntime,
) {
    fun runtimeFor(
        settings: AppSettings,
        environment: String?,
    ): LocalRuntime? {
        val requested = environment?.trim().orEmpty().lowercase()
        val runtimeId = when (requested) {
            "", "default", "termux", "alpine" -> settings.defaultRuntimeId
                ?: chooseOnlyEnabled(settings)
                ?: legacyDefault(settings)
            else -> null
        } ?: return null

        if (settings.enabledRuntimeIds.isNotEmpty()) {
            if (runtimeId !in settings.enabledRuntimeIds) return null
        }
        return runtimeById(runtimeId)
    }

    fun runtimeById(runtimeId: LocalRuntimeId): LocalRuntime = termuxRuntime

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
        return termuxWorkspaceDirectory
    }

    fun setupRequiredError(environment: String? = null): String =
        JSONObject().apply {
            put("ok", false)
            put("errmsg", "No local runtime is configured for this tool call.")
            put("hint", "Configure Termux in Settings after setup.")
            if (!environment.isNullOrBlank()) put("environment", environment)
        }.toString()

    private fun chooseOnlyEnabled(settings: AppSettings): LocalRuntimeId? =
        settings.enabledRuntimeIds.singleOrNull()

    private fun legacyDefault(settings: AppSettings): LocalRuntimeId? =
        if (settings.termuxSetupCompleted) LocalRuntimeId.Termux else null
}

fun JSONObject.runtimeEnvironment(): String =
    optString("environment").trim()
