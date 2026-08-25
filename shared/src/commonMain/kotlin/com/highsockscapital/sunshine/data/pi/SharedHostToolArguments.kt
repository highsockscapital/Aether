package com.highsockscapital.sunshine.data.pi

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Session-scoping key injected into shared host-tool executor arguments.
 *
 * Previously lived in SharedPiChatClient.kt; restored standalone because
 * SharedChromeManager and SharedMcpManager still resolve the calling session
 * from this key when dispatching delegated tools.
 */
private const val SharedHostToolSessionIdArgument = "__sunshine_session_id"

internal fun JsonObject.sharedHostToolSessionId(): String =
    get(SharedHostToolSessionIdArgument)?.jsonPrimitive?.contentOrNull.orEmpty()
