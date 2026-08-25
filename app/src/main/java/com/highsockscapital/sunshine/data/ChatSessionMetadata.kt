package com.highsockscapital.sunshine.data

import com.highsockscapital.sunshine.ui.ChatMessage
import com.highsockscapital.sunshine.ui.ChatSession
import com.highsockscapital.sunshine.ui.MessageDisplayKind
import com.highsockscapital.sunshine.ui.MessageAuthor
import com.highsockscapital.sunshine.ui.syncActiveBranches

internal fun ChatSession.withDerivedMessages(
    messages: List<ChatMessage>,
): ChatSession {
    val metadata = deriveSessionMetadata(messages)
    return copy(
        title = if (hasCustomTitle) title else metadata.first,
        preview = metadata.second,
        messages = syncActiveBranches(messages),
        messageCount = messages.size,
        lastMessageAtMillis = messages.maxOfOrNull { it.createdAtMillis },
    )
}

private fun deriveSessionMetadata(messages: List<ChatMessage>): Pair<String, String> {
    val visibleMessages = messages.filter { it.displayKind != MessageDisplayKind.HiddenContext }
    val title = messages
        .firstOrNull { it.author == MessageAuthor.User && it.displayKind == MessageDisplayKind.Standard }
        ?.summaryText()
        .orEmpty()
        .ifBlank { "New chat" }
        .take(36)
    val preview = visibleMessages
        .lastOrNull()
        ?.summaryText()
        .orEmpty()
        .ifBlank { "No messages yet." }
        .take(96)
    return title to preview
}

internal fun ChatMessage.summaryText(): String {
    if (displayKind == MessageDisplayKind.CompactStatus) return text.ifBlank { "Context compacted" }
    val textSummary = text.trim()
    if (textSummary.isNotBlank()) return textSummary
    reasoningTrace?.let { trace ->
        trace.chunks.lastOrNull { it.detail.isNotBlank() || it.title.isNotBlank() }?.let { chunk ->
            return chunk.detail.ifBlank { chunk.title }
        }
        return if (trace.toolInvocations.isNotEmpty()) {
            "Thought and used ${trace.toolInvocations.size} tools"
        } else {
            "Thought"
        }
    }
    if (toolInvocations.isNotEmpty()) {
        return if (toolInvocations.size == 1) {
            when (toolInvocations.first().toolName.lowercase()) {
                "bash" -> "Ran bash command"
                else -> "Used ${toolInvocations.first().toolName}"
            }
        } else {
            "Used ${toolInvocations.size} tools"
        }
    }
    if (attachments.isEmpty()) return "Empty message"
    if (attachments.size == 1) return attachments.first().name
    return "${attachments.size} attachments"
}
