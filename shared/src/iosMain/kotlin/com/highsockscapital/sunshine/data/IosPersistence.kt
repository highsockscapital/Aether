package com.highsockscapital.sunshine.data

import com.highsockscapital.sunshine.data.chatdb.ChatHistoryDatabase
import com.highsockscapital.sunshine.data.chatdb.createIosChatHistoryDatabase
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSFileManager
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
private fun iosApplicationSupportDirectory(): String {
    val path = NSHomeDirectory() + "/Library/Application Support"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}

fun createIosSunshineSettingsStore(): SunshineSettingsStore =
    createSunshineSettingsStore(
        iosApplicationSupportDirectory() + "/sunshine_settings.preferences_pb",
    )

fun createIosSunshineChatHistoryDatabase(): ChatHistoryDatabase =
    createIosChatHistoryDatabase(
        iosApplicationSupportDirectory() + "/sunshine_chat_history.db",
    )
