package com.highsockscapital.sunshine

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.highsockscapital.sunshine.data.AppLanguage
import com.highsockscapital.sunshine.data.appLanguageForTag
import com.highsockscapital.sunshine.data.defaultAppLanguage

object SunshineLocaleManager {
    fun apply(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(language.languageTag),
        )
    }

    fun applyIfChanged(language: AppLanguage) {
        if (currentApplicationLanguage() == language) return
        apply(language)
    }

    fun currentApplicationLanguage(): AppLanguage? =
        AppCompatDelegate.getApplicationLocales().get(0)
            ?.toLanguageTag()
            ?.let(::appLanguageForTag)

    fun currentLanguage(): AppLanguage =
        currentApplicationLanguage() ?: defaultAppLanguage()
}
