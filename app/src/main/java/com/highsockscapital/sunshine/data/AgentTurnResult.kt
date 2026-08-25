package com.highsockscapital.sunshine.data

data class SunshineAgentTurnResult(
    val assistantText: String,
    val tokenUsage: LlmTokenUsage? = null,
    val providerPayloadJson: String = "",
    val piSessionId: String = "",
    val piSessionFile: String = "",
    val piSessionLeafId: String = "",
    val runtime: String = "",
    val cwd: String = "",
    val piEntryIds: List<String> = emptyList(),
)

data class AgentToolEvent(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val outputJson: String? = null,
    val isRunning: Boolean? = null,
)

data class StreamingStatus(
    val text: String,
    val detail: String = "",
)
