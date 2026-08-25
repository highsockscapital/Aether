package com.highsockscapital.sunshine.platform

import com.highsockscapital.sunshine.data.AppLanguage

expect fun platformAppVersion(): String

/**
 * Updates the locale source consumed by Compose Resources. The app content is
 * keyed by the selected language, so the new environment is read immediately.
 */
expect fun applyPlatformAppLanguage(language: AppLanguage)

