package com.highsockscapital.sunshine.data

import com.posthog.PostHog

object SunshineAnalytics {
    fun capture(
        event: String,
        properties: Map<String, Any> = emptyMap(),
    ) {
        runCatching {
            if (properties.isEmpty()) {
                PostHog.capture(event = event)
            } else {
                PostHog.capture(event = event, properties = properties)
            }
        }
    }

    fun captureException(
        throwable: Throwable,
        properties: Map<String, Any> = emptyMap(),
    ) {
        runCatching {
            PostHog.captureException(throwable, properties)
        }
    }
}
