package com.highsockscapital.sunshine.data

import com.highsockscapital.sunshine.runtime.RuntimeRouter
import org.json.JSONArray
import org.json.JSONObject

data class SunshineToolExecutionResult(
    val toolName: String,
    val argumentsJson: String,
    val rawOutput: String,
    val visibleOutput: String = SunshineToolExecutor.sanitizeToolOutputForConversation(toolName, rawOutput),
) {
    val isError: Boolean = !SunshineToolExecutor.inferToolOutputOk(visibleOutput)
}

/**
 * Adapter for Sunshine-owned host capabilities only. Pi Coding Agent owns all
 * filesystem, shell, search, Skill, Extension, and image-reading mechanics.
 */
class SunshineToolExecutor(
    private val runtimeRouter: RuntimeRouter,
    private val agentModeController: AgentModeController? = null,
) {
    suspend fun execute(
        settings: AppSettings,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        toolName: String,
        argumentsJson: String,
        selfManagementTool: SunshineSelfManagementTool? = null,
        agentModeEnabled: Boolean = false,
        currentRuntimeId: LocalRuntimeId = settings.defaultRuntimeId ?: LocalRuntimeId.Termux,
        onRuntimeChanged: suspend (LocalRuntimeId) -> Unit = {},
        onProgress: (suspend (String) -> Unit)? = null,
    ): SunshineToolExecutionResult {
        val rawOutput = when (toolName) {
            "agent_display" -> if (agentModeEnabled) {
                agentModeController?.execute(
                    settings = settings,
                    workspaceDirectory = workspaceDirectory,
                    termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                    argumentsJson = argumentsJson,
                ) ?: unavailableToolOutput(toolName)
            } else {
                JSONObject()
                    .put("ok", false)
                    .put("errmsg", "Agent Mode is not enabled for this chat.")
                    .toString()
            }

            "sunshine_screen" -> executeScreen(argumentsJson)

            "sunshine_runtime_manage" -> executeRuntimeManage(
                settings = settings,
                currentRuntimeId = currentRuntimeId,
                workspaceDirectory = workspaceDirectory,
                termuxWorkspaceDirectory = termuxWorkspaceDirectory,
                argumentsJson = argumentsJson,
                onRuntimeChanged = onRuntimeChanged,
            )

            in SelfManagementToolNames -> selfManagementTool?.execute(
                toolName = toolName,
                argumentsJson = argumentsJson,
            ) ?: unavailableToolOutput(toolName)

            else -> JSONObject()
                .put("ok", false)
                .put("error", "Unknown Sunshine host tool '$toolName'.")
                .toString()
        }
        return SunshineToolExecutionResult(toolName, argumentsJson, rawOutput)
    }

    private suspend fun executeRuntimeManage(
        settings: AppSettings,
        currentRuntimeId: LocalRuntimeId,
        workspaceDirectory: String,
        termuxWorkspaceDirectory: String,
        argumentsJson: String,
        onRuntimeChanged: suspend (LocalRuntimeId) -> Unit,
    ): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("errmsg", "Invalid JSON arguments.").toString()
        val action = arguments.optString("action").trim()
        if (action == "status") {
            val states = LocalRuntimeId.entries.associateWith { runtimeId ->
                runtimeRouter.runtimeById(runtimeId).inspectSetup()
            }
            return JSONObject().apply {
                put("ok", true)
                put("action", "status")
                put("runtime", currentRuntimeId.storageValue)
                put("cwd", runtimeCwd(currentRuntimeId, workspaceDirectory, termuxWorkspaceDirectory))
                put(
                    "available",
                    JSONObject().apply {
                        states.forEach { (runtimeId, state) -> put(runtimeId.storageValue, state.isReady) }
                    },
                )
            }.toString()
        }
        if (action != "set") {
            return JSONObject().put("ok", false).put("errmsg", "action must be 'status' or 'set'.").toString()
        }
        val requested = LocalRuntimeId.fromStorage(arguments.optString("runtime"))
            ?: return JSONObject().put("ok", false).put("errmsg", "runtime must be 'termux'.").toString()
        val setup = runtimeRouter.runtimeById(requested).inspectSetup()
        val enabled = settings.enabledRuntimeIds.isEmpty() || requested in settings.enabledRuntimeIds
        if (!setup.isReady || !enabled) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "${requested.displayName} runtime is unavailable.")
                put("detail", setup.detail)
                put("runtime", currentRuntimeId.storageValue)
                put("cwd", runtimeCwd(currentRuntimeId, workspaceDirectory, termuxWorkspaceDirectory))
            }.toString()
        }
        onRuntimeChanged(requested)
        return JSONObject().apply {
            put("ok", true)
            put("action", "set")
            put("runtime", requested.storageValue)
            put("cwd", runtimeCwd(requested, workspaceDirectory, termuxWorkspaceDirectory))
        }.toString()
    }

    private fun executeScreen(argumentsJson: String): String {
        val service = com.highsockscapital.sunshine.accessibility.SunshineAccessibilityService.instance
            ?: return JSONObject()
                .put("ok", false)
                .put("errmsg", "Sunshine's accessibility engine is off. Enable it in system settings.")
                .toString()

        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("errmsg", "Invalid JSON arguments.").toString()
        val action = arguments.optString("action").trim()

        fun ok(extra: JSONObject.() -> Unit = {}): String =
            JSONObject().put("ok", true).put("action", action).apply(extra).toString()

        fun err(message: String): String =
            JSONObject().put("ok", false).put("errmsg", message).toString()

        return when (action) {
            "describe" -> {
                val lines = service.describeScreen()
                if (lines.isEmpty()) err("No active window or the service cannot see it yet.")
                else ok {
                    put("nodes", lines.size)
                    put("screen", lines.take(400).joinToString("\n"))
                    if (lines.size > 400) put("truncated", lines.size - 400)
                }
            }
            "focus" -> {
                val focused = service.rootInActiveWindow
                    ?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                ok {
                    put(
                        "focused_text",
                        focused?.text?.toString() ?: JSONObject.NULL,
                    )
                    put(
                        "focused_class",
                        focused?.className?.toString() ?: JSONObject.NULL,
                    )
                }
            }
            "tap" -> {
                val x = arguments.optDouble("x", Double.NaN)
                val y = arguments.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN()) {
                    err("tap requires x and y.")
                } else {
                    val okBool = service.tapAt(x.toFloat(), y.toFloat())
                    ok { put("dispatched", okBool) }
                }
            }
            "swipe" -> {
                val x1 = arguments.optDouble("x1", Double.NaN)
                val y1 = arguments.optDouble("y1", Double.NaN)
                val x2 = arguments.optDouble("x2", Double.NaN)
                val y2 = arguments.optDouble("y2", Double.NaN)
                if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) {
                    err("swipe requires x1, y1, x2, y2.")
                } else {
                    val duration = arguments.optLong("duration_ms", 300L)
                    val okBool = service.swipe(
                        x1.toFloat(), y1.toFloat(),
                        x2.toFloat(), y2.toFloat(), duration
                    )
                    ok { put("dispatched", okBool) }
                }
            }
            "click_text" -> {
                val query = arguments.optString("text").trim()
                if (query.isEmpty()) err("click_text requires 'text'.")
                else ok { put("clicked", service.clickText(query)) }
            }
            "click_id" -> {
                val query = arguments.optString("id").trim()
                if (query.isEmpty()) err("click_id requires 'id'.")
                else ok { put("clicked", service.clickViewId(query)) }
            }
            "type" -> {
                val text = arguments.optString("text")
                ok { put("typed", service.typeIntoFocused(text)) }
            }
            "scroll_forward" -> ok { put("scrolled", service.scrollForward()) }
            "scroll_backward" -> ok { put("scrolled", service.scrollBackward()) }
            "back" -> ok { put("pressed", service.pressBack()) }
            "home" -> ok { put("pressed", service.pressHome()) }
            "recents" -> ok { put("pressed", service.openRecents()) }
            "notifications" -> ok { put("opened", service.openNotifications()) }
            else -> err("Unknown sunshine_screen action '$action'.")
        }
    }

    companion object {
        val hostToolNames: Set<String> = setOf(
            "agent_display",
            "sunshine_screen",
            *SelfManagementToolNames.toTypedArray(),
        )

        fun supports(toolName: String): Boolean = toolName in hostToolNames

        fun hostToolDefinitions(
            selfManagementTool: SunshineSelfManagementTool? = null,
            agentModeEnabled: Boolean = false,
        ): JSONArray = JSONArray().apply {
            selfManagementTool?.toolDefinitions()?.forEach { definition ->
                put(
                    flattenOpenAiToolDefinition(
                        definition = definition,
                        executionMode = if (
                            definition.optJSONObject("function")?.optString("name") == "sunshine_config_get"
                        ) "parallel" else "sequential",
                    ),
                )
            }
            put(screenToolDefinition())
            if (agentModeEnabled) put(agentModeToolDefinition())
        }

        fun sanitizeToolOutputForConversation(toolName: String, output: String): String {
            if (toolName != "agent_display") return output
            val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return output
            if (!parsed.has("screenshot_base64")) return output
            parsed.remove("screenshot_base64")
            parsed.put("screenshot_injected_into_next_model_request", true)
            return parsed.toString()
        }

        fun inferToolOutputOk(output: String): Boolean {
            val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return true
            return parsed.optBoolean("ok", !parsed.optBoolean("err", false))
        }
    }
}

private fun runtimeCwd(
    runtimeId: LocalRuntimeId,
    workspaceDirectory: String,
    termuxWorkspaceDirectory: String,
): String = if (runtimeId == LocalRuntimeId.Termux) termuxWorkspaceDirectory else workspaceDirectory

private val SelfManagementToolNames = setOf(
    "sunshine_config_get",
    "sunshine_config_set",
    "sunshine_skill_manage",
    "sunshine_termux_manage",
    "sunshine_runtime_manage",
    "sunshine_agent_mode_manage",
    "sunshine_scheduled_task_manage",
    "sunshine_extension_manage",
    "sunshine_developer_manage",
)

private fun unavailableToolOutput(toolName: String): String = JSONObject()
    .put("ok", false)
    .put("errmsg", "Host dependency for '$toolName' is not available.")
    .toString()

private fun flattenOpenAiToolDefinition(
    definition: JSONObject,
    executionMode: String,
): JSONObject {
    val function = definition.optJSONObject("function") ?: JSONObject()
    return JSONObject().apply {
        put("name", function.optString("name"))
        put("description", function.optString("description"))
        put("parameters", relaxStrictOptionalParameters(function.optJSONObject("parameters")))
        put("execution_mode", executionMode)
    }
}

private fun relaxStrictOptionalParameters(parameters: JSONObject?): JSONObject {
    val relaxed = JSONObject((parameters ?: JSONObject().put("type", "object")).toString())
    val properties = relaxed.optJSONObject("properties") ?: return relaxed
    val required = relaxed.optJSONArray("required") ?: return relaxed
    relaxed.put(
        "required",
        JSONArray().apply {
            for (index in 0 until required.length()) {
                val name = required.optString(index)
                if (name.isNotBlank() && !properties.optJSONObject(name).allowsNull()) put(name)
            }
        },
    )
    return relaxed
}

private fun JSONObject?.allowsNull(): Boolean = when (val type = this?.opt("type")) {
    "null" -> true
    is JSONArray -> (0 until type.length()).any { type.optString(it) == "null" }
    else -> false
}

private fun agentModeToolDefinition(): JSONObject = JSONObject().apply {
    put("name", "agent_display")
    put(
        "description",
        "Operate Sunshine Agent Mode on an isolated Android virtual display. Use this only when Agent Mode is selected in the chat composer.",
    )
    put(
        "parameters",
        JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put("action", stringProperty("One of: list_apps, start, status, launch, tap, swipe, key, text, screenshot, stop."))
                    put("query", stringProperty("For list_apps: optional app label, package, or activity filter."))
                    put("include_system", booleanProperty("For list_apps: whether to include system apps."))
                    put("max_results", integerProperty("For list_apps: maximum number of apps to return."))
                    put("target", stringProperty("For launch: package name or exact app label."))
                    listOf("x", "y", "x1", "y1", "x2", "y2", "duration_ms").forEach { key ->
                        put(key, integerProperty("Normalized coordinate or gesture duration for $key."))
                    }
                    put("key", stringProperty("For key: Android key code name or number."))
                    put("text", stringProperty("For text: text to type into the focused field."))
                },
            )
            put("required", JSONArray().put("action"))
            put("additionalProperties", false)
        },
    )
    put("execution_mode", "sequential")
}

private fun screenToolDefinition(): JSONObject = JSONObject().apply {
    put("name", "sunshine_screen")
    put(
        "description",
        "See and act on whatever is on the device's screen through Sunshine's accessibility engine. " +
            "Use 'describe' to get the current window's node tree (eyes). " +
            "Use 'focus' to see which input field has focus. " +
            "Use 'tap', 'swipe', 'click_text', 'click_id', 'type' to act (hands). " +
            "Use 'scroll_forward'/'scroll_backward', 'back', 'home', 'recents', 'notifications' to navigate. " +
            "Sunshine refuses to look at or act on apps in the sensitive blacklist.",
    )
    put(
        "parameters",
        JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().apply {
                    put(
                        "action",
                        stringProperty(
                            "One of: describe, focus, tap, swipe, click_text, click_id, " +
                                "type, scroll_forward, scroll_backward, back, home, recents, notifications."
                        )
                    )
                    put("x", floatProperty("For tap: screen x coordinate in pixels."))
                    put("y", floatProperty("For tap: screen y coordinate in pixels."))
                    put("x1", floatProperty("For swipe: start x."))
                    put("y1", floatProperty("For swipe: start y."))
                    put("x2", floatProperty("For swipe: end x."))
                    put("y2", floatProperty("For swipe: end y."))
                    put("duration_ms", integerProperty("For swipe: duration in milliseconds (default 300)."))
                    put("text", stringProperty("For click_text: visible label. For type: text to inject."))
                    put("id", stringProperty("For click_id: view id suffix, e.g. 'btn_search'."))
                },
            )
            put("required", JSONArray().put("action"))
            put("additionalProperties", false)
        },
    )
    put("execution_mode", "sequential")
}

private fun stringProperty(description: String): JSONObject = JSONObject()
    .put("type", "string")
    .put("description", description)

private fun integerProperty(description: String): JSONObject = JSONObject()
    .put("type", "integer")
    .put("description", description)

private fun floatProperty(description: String): JSONObject = JSONObject()
    .put("type", "number")
    .put("description", description)

private fun booleanProperty(description: String): JSONObject = JSONObject()
    .put("type", "boolean")
    .put("description", description)
