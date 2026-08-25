package com.highsockscapital.sunshine.platform

import com.highsockscapital.sunshine.data.AppLanguage
import java.util.Locale

actual fun platformAppVersion(): String = "Android"

actual fun applyPlatformAppLanguage(language: AppLanguage) {
    Locale.setDefault(Locale.forLanguageTag(language.languageTag))
}

