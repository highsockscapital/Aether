package com.highsockscapital.sunshine.data.pi

import com.highsockscapital.sunshine.data.AppSettings
import com.highsockscapital.sunshine.data.LlmImagePart
import com.highsockscapital.sunshine.data.LlmMessage
import com.highsockscapital.sunshine.data.LlmTextPart
import com.highsockscapital.sunshine.data.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject

class PiCompletionClient(
    private val bridge: PiKernelBridge,
    private val settingsRepository: SettingsRepository? = null,
) {
    suspend fun completeOnce(
        settings: AppSettings,
        systemPrompt: String,
        messages: List<LlmMessage>,
        disableReasoning: Boolean = false,
        stream: Boolean = false,
        thinkingLevelMap: Map<String, String> = emptyMap(),
        isReasoningModel: Boolean? = null,
        onEvent: (suspend (String, JSONObject) -> Unit)? = null,
    ): Result<PiCompletionResult> = runCatching {
        val payload = JSONObject().apply {
            put(
                "model_config",
                settings.toPiModelConfig(
                    thinkingLevelMap = thinkingLevelMap,
                    isReasoningModel = isReasoningModel,
                ).toJson(),
            )
            put("system_prompt", systemPrompt)
            put("messages", messages.toPiJson())
            put("stream", stream)
            put("reasoning", if (disableReasoning) "off" else settings.toPiThinkingLevel())
        }
        bridge.completeOnce(payload, onEvent).toPiCompletionResult().also { result ->
            if (
                settings.providerConfigId.isNotBlank() &&
                result.updatedOauthCredentialJson.isNotBlank()
            ) {
                settingsRepository?.updateProviderOAuthCredential(
                    settings.providerConfigId,
                    result.updatedOauthCredentialJson,
                )
            }
        }
    }
}

fun List<LlmMessage>.toPiJson(): JSONArray = JSONArray().apply {
    forEach { message ->
        put(message.toPiJson())
    }
}

fun LlmMessage.toPiJson(): JSONObject = JSONObject().apply {
    put("role", role)
    val content = JSONArray()
    contentParts.forEach { part ->
        when (part) {
            is LlmTextPart -> content.put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", part.text)
                }
            )
            is LlmImagePart -> content.put(
                JSONObject().apply {
                    put("type", "image")
                    put("mime_type", part.mimeType)
                    put("data", part.base64Data)
                }
            )
        }
    }
    put("content", content)
    if (toolCallId.isNotBlank()) put("tool_call_id", toolCallId)
    if (toolName.isNotBlank()) put("tool_name", toolName)
    if (isError) put("is_error", true)
    providerPayload?.let { put("provider_payload", it) }
}
