package com.highsockscapital.sunshine.data.pi

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun createChromeHttpClient(): HttpClient = HttpClient {
    install(WebSockets)
}
