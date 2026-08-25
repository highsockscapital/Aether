package com.highsockscapital.sunshine.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmProviderConfigValidationTest {
    @Test
    fun modelListsPutMajorFamiliesFirstThenSortAlphabetically() {
        assertEquals(
            listOf(
                "gpt-4o",
                "gpt-5",
                "claude-3-haiku",
                "claude-sonnet-4",
                "gemini-2.5-flash",
                "alpha",
                "Mistral-Large",
                "zeta",
            ),
            listOf(
                "zeta",
                "claude-sonnet-4",
                "Mistral-Large",
                "gemini-2.5-flash",
                "gpt-5",
                "alpha",
                "claude-3-haiku",
                "gpt-4o",
            ).sortedByPreferredModelName(),
        )
    }

    @Test
    fun namespacedModelIdsUseTheModelNameForFamilyPriority() {
        assertEquals(
            listOf("openai/gpt-5", "anthropic/claude-opus-4", "vendor/alpha"),
            listOf(
                "vendor/alpha",
                "anthropic/claude-opus-4",
                "openai/gpt-5",
            ).sortedByPreferredModelName(),
        )
    }

    @Test
    fun builtInApiKeyProviderRequiresSupportedNonBlankKey() {
        // OpenRouter is the remaining built-in API-key provider.
        assertFalse(provider(piProviderId = "openrouter", apiKey = "").isSharedProviderSetupValid())
        assertTrue(provider(piProviderId = "openrouter", apiKey = "secret").isSharedProviderSetupValid())

        // Unknown provider ids resolve to the custom endpoint fallback, which
        // accepts any configured key plus its default base URL.
        assertTrue(
            provider(
                piProviderId = "openai-codex",
                authMethod = ProviderAuthMethod.ApiKey,
                apiKey = "secret",
            ).isSharedProviderSetupValid(),
        )
    }

    @Test
    fun oauthProviderRequiresSupportAndCredential() {
        // No catalog provider currently declares supportsOAuth, so OAuth
        // setups stay invalid even with a credential until one adds support.
        assertFalse(
            provider(
                piProviderId = "openrouter",
                authMethod = ProviderAuthMethod.OAuth,
            ).isSharedProviderSetupValid(),
        )
        assertFalse(
            provider(
                piProviderId = "openrouter",
                authMethod = ProviderAuthMethod.OAuth,
                oauthCredentialJson = "{\"access\":\"token\"}",
            ).isSharedProviderSetupValid(),
        )
    }

    @Test
    fun ambientProviderRequiresCatalogSupport() {
        // No catalog provider currently declares supportsAmbientAuth.
        assertFalse(
            provider(
                piProviderId = "google-vertex",
                authMethod = ProviderAuthMethod.Ambient,
                baseUrl = "",
            ).isSharedProviderSetupValid(),
        )
        assertFalse(
            provider(
                piProviderId = "openai",
                authMethod = ProviderAuthMethod.Ambient,
            ).isSharedProviderSetupValid(),
        )
    }

    @Test
    fun customProviderMatchesAndroidBaseUrlAndApiKeyRules() {
        assertFalse(
            provider(
                piProviderId = "openai-compatible",
                apiKey = "",
                baseUrl = "",
            ).isSharedProviderSetupValid(),
        )
        assertTrue(
            provider(
                piProviderId = "openai-compatible",
                apiKey = "",
                baseUrl = "https://models.example/v1",
            ).isSharedProviderSetupValid(),
        )
    }

    @Test
    fun requiredBuiltInBaseUrlIsValidated() {
        assertFalse(
            provider(
                piProviderId = "azure-openai-responses",
                apiKey = "secret",
                baseUrl = " ",
            ).isSharedProviderSetupValid(),
        )
        assertTrue(
            provider(
                piProviderId = "azure-openai-responses",
                apiKey = "secret",
                baseUrl = "https://example.openai.azure.com",
            ).isSharedProviderSetupValid(),
        )
    }
}

private fun provider(
    piProviderId: String,
    authMethod: ProviderAuthMethod = ProviderAuthMethod.ApiKey,
    apiKey: String = "",
    baseUrl: String = PiProviderCatalog.resolve(piProviderId).defaultBaseUrl,
    oauthCredentialJson: String = "",
) = LlmProviderConfig(
    providerId = "provider",
    name = "Provider",
    piProviderId = piProviderId,
    apiKey = apiKey,
    baseUrl = baseUrl,
    authMethod = authMethod,
    oauthCredentialJson = oauthCredentialJson,
    modelId = "model",
)
