package com.highsockscapital.sunshine

import androidx.compose.ui.window.ComposeUIViewController
import com.highsockscapital.sunshine.platform.currentPlatformCapabilities
import com.highsockscapital.sunshine.runtime.IosAlpineRuntime
import com.highsockscapital.sunshine.runtime.NativeRuntimeHost
import com.highsockscapital.sunshine.ui.IosComposeApp
import com.highsockscapital.sunshine.data.createIosSunshineSettingsStore
import com.highsockscapital.sunshine.data.createIosSunshineChatHistoryDatabase
import com.highsockscapital.sunshine.platform.IosPlatformServices
import com.highsockscapital.sunshine.platform.IosNativeSettingsHost

fun MainViewController(runtimeHost: NativeRuntimeHost): platform.UIKit.UIViewController {
    val runtime = IosAlpineRuntime(runtimeHost)
    val settingsStore = createIosSunshineSettingsStore()
    val chatHistoryDatabase = createIosSunshineChatHistoryDatabase()
    val platformServices = IosPlatformServices(runtimeHost)
    return ComposeUIViewController {
        IosComposeApp(
            runtime = runtime,
            capabilities = currentPlatformCapabilities,
            settingsStore = settingsStore,
            chatHistoryDatabase = chatHistoryDatabase,
            platformServices = platformServices,
            nativeSettingsHost = IosNativeSettingsHost,
        )
    }
}
