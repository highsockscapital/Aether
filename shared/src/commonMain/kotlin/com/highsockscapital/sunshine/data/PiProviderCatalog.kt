package com.highsockscapital.sunshine.data

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderAuthMethod(
    val storageValue: String,
) {
    ApiKey("api_key"),
    OAuth("oauth"),
    Ambient("ambient");

    companion object {
        fun fromStorage(
            value: String?,
            defaultValue: ProviderAuthMethod = ApiKey,
        ): ProviderAuthMethod =
            entries.firstOrNull { it.storageValue == value } ?: defaultValue
    }
}

@Serializable
data class PiProviderEnvironmentVariable(
    val name: String,
    val value: String,
)

data class PiProviderDefinition(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModelId: String,
    val supportsApiKey: Boolean = true,
    val supportsInteractiveApiKey: Boolean = supportsApiKey,
    val supportsOAuth: Boolean = false,
    val supportsAmbientAuth: Boolean = false,
    val requiresBaseUrl: Boolean = false,
    val supportsCustomBaseUrl: Boolean = false,
    val isBuiltIn: Boolean = true,
    val category: String = "Other",
    val hiddenFromPickers: Boolean = false,
)

const val DefaultPiProviderId = "openai-compatible"
const val DefaultCustomProviderBaseUrl = "https://api.openai.com/v1"
const val DefaultCustomModelId = "gpt-5.4"

object PiProviderCatalog {
    val providers: List<PiProviderDefinition> = listOf(
        builtin(
            "openrouter",
            "OpenRouter",
            "https://openrouter.ai/api/v1",
            "openai/gpt-5.4",
            category = "Recommended",
        ),
        // Kept as the resolve() fallback for unknown/legacy provider ids.
        // Hidden from all provider pickers.
        custom(DefaultPiProviderId, "OpenAI-compatible endpoint").copy(
            hiddenFromPickers = true,
        ),
    )

    val builtInProviders: List<PiProviderDefinition>
        get() = providers.filter(PiProviderDefinition::isBuiltIn)

    val recommendedProviders: List<PiProviderDefinition>
        get() = providers.filter { it.category == "Recommended" }

    fun find(id: String?): PiProviderDefinition? =
        providers.firstOrNull { it.id == id?.trim() }

    fun resolve(id: String?): PiProviderDefinition =
        find(id) ?: providers.first { it.id == DefaultPiProviderId }
}

fun PiProviderDefinition.modelsDevProviderIds(): List<String> = listOf(id)

fun inferLegacyPiProviderId(
    legacyProviderStorageValue: String?,
    baseUrl: String,
): String = when (legacyProviderStorageValue?.trim()) {
    "openai_responses" -> "openai"
    "anthropic_messages" -> "anthropic"
    "vertex_express" -> "google-vertex"
    else -> builtInProviderIdForHost(hostOf(baseUrl)) ?: DefaultPiProviderId
}

fun PiProviderDefinition.defaultAuthMethod(): ProviderAuthMethod = when {
    !supportsApiKey && supportsOAuth -> ProviderAuthMethod.OAuth
    supportsAmbientAuth && !supportsInteractiveApiKey -> ProviderAuthMethod.Ambient
    else -> ProviderAuthMethod.ApiKey
}

private fun builtin(
    id: String,
    displayName: String,
    defaultBaseUrl: String,
    defaultModelId: String,
    supportsApiKey: Boolean = true,
    supportsInteractiveApiKey: Boolean = supportsApiKey,
    supportsOAuth: Boolean = false,
    supportsAmbientAuth: Boolean = false,
    requiresBaseUrl: Boolean = false,
    supportsCustomBaseUrl: Boolean = false,
    category: String,
): PiProviderDefinition = PiProviderDefinition(
    id = id,
    displayName = displayName,
    defaultBaseUrl = defaultBaseUrl,
    defaultModelId = defaultModelId,
    supportsApiKey = supportsApiKey,
    supportsInteractiveApiKey = supportsInteractiveApiKey,
    supportsOAuth = supportsOAuth,
    supportsAmbientAuth = supportsAmbientAuth,
    requiresBaseUrl = requiresBaseUrl,
    supportsCustomBaseUrl = supportsCustomBaseUrl,
    category = category,
)

private fun custom(
    id: String,
    displayName: String,
): PiProviderDefinition = PiProviderDefinition(
    id = id,
    displayName = displayName,
    defaultBaseUrl = DefaultCustomProviderBaseUrl,
    defaultModelId = DefaultCustomModelId,
    supportsApiKey = true,
    requiresBaseUrl = true,
    isBuiltIn = false,
    category = "Custom",
)

private fun hostOf(baseUrl: String): String {
    val authority = baseUrl.trim()
        .substringAfter("://", missingDelimiterValue = "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
    return authority.substringBefore(':').lowercase()
}

// Only hosts whose provider still exists in the catalog need an entry;
// everything else falls back to the custom endpoint definition.
private fun builtInProviderIdForHost(host: String): String? = when (host) {
    "openrouter.ai" -> "openrouter"
    else -> null
}
