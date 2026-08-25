package com.highsockscapital.sunshine.ui

import android.net.Uri

internal const val AssistantLocalFileLinkScheme = "sunshine-local-file"

internal fun buildAssistantLocalFileLink(path: String): String =
    "$AssistantLocalFileLinkScheme://${Uri.encode(path)}"

internal fun parseAssistantLocalFileLink(rawLink: String): String? {
    val prefix = "$AssistantLocalFileLinkScheme://"
    if (!rawLink.startsWith(prefix, ignoreCase = true)) return null
    return Uri.decode(rawLink.substring(prefix.length)).trim().ifBlank { null }
}
