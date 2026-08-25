package com.highsockscapital.sunshine.ui

import com.highsockscapital.sunshine.data.PiProviderDefinition
import com.highsockscapital.sunshine.data.SunshineLlmUserAgent
import com.highsockscapital.sunshine.data.LlmProviderConfig
import com.highsockscapital.sunshine.data.PiProviderCatalog
import com.highsockscapital.sunshine.data.PiProviderEnvironmentVariable
import com.highsockscapital.sunshine.data.ProviderAuthMethod
import com.highsockscapital.sunshine.data.availableModels
import com.highsockscapital.sunshine.data.pi.PiProviderAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigFormTest {
    @Test
    fun catalogIncludesEveryPiBuiltInProviderAndCustomEndpoint() {
        assertEquals(1, PiProviderCatalog.builtInProviders.size)
        assertEquals("openrouter", PiProviderCatalog.builtInProviders.single().id)
        assertEquals(2, PiProviderCatalog.providers.size)

        val customProviders = PiProviderCatalog.providers.filterNot { it.isBuiltIn }
        assertEquals(1, customProviders.size)
        assertEquals("openai-compatible", customProviders.single().id)
    }

    @Test
    fun applyProviderDefaultsHonorsDefinitionAuthCapabilities() {
        // Definitions that only offer ambient authentication default to it.
        val cloudDefinition = PiProviderDefinition(
            id = "test-cloud",
            displayName = "Test Cloud",
            defaultBaseUrl = "",
            defaultModelId = "",
            supportsApiKey = true,
            supportsInteractiveApiKey = false,
            supportsAmbientAuth = true,
        )
        val cloudState = ProviderFormState.fromConfig(null)
        cloudState.applyProviderDefaults(cloudDefinition)
        assertEquals(ProviderAuthMethod.Ambient, cloudState.authMethod)

        // Key-based definitions with an optional base URL keep it blank and
        // still accept an API key. Configured-ness of unknown ids against the
        // catalog is covered by OnboardingLogicTest.
        val gatewayDefinition = PiProviderDefinition(
            id = "cloudflare-ai-gateway",
            displayName = "Cloudflare AI Gateway",
            defaultBaseUrl = "",
            defaultModelId = "",
            requiresBaseUrl = false,
        )
        val gatewayState = ProviderFormState.fromConfig(null)
        gatewayState.applyProviderDefaults(gatewayDefinition)
        gatewayState.apiKey = "test-key"

        assertFalse(gatewayState.selectedDefinition.requiresBaseUrl)
        assertEquals("", gatewayState.baseUrl)
        val config = gatewayState.buildConfig()
        assertEquals("test-key", config.apiKey)
        assertEquals("", config.baseUrl)
    }

    @Test
    fun ensureAvailableProviderIdUsesNextUnusedSuffix() {
        val state = ProviderFormState.fromConfig(null)

        state.applyProviderDefaults(PiProviderCatalog.resolve("openrouter"))
        state.ensureAvailableProviderId(setOf("openrouter", "openrouter_2", "openrouter_3"))

        assertEquals("openrouter_4", state.providerId)
    }

    @Test
    fun providerAuthResultAppliesApiKeyAndProviderEnvironment() {
        val state = ProviderFormState.fromConfig(null)
        val environment = listOf(
            PiProviderEnvironmentVariable("CLOUDFLARE_ACCOUNT_ID", "account"),
            PiProviderEnvironmentVariable("CLOUDFLARE_GATEWAY_ID", "gateway"),
        )

        state.applyProviderDefaults(PiProviderCatalog.resolve("cloudflare-ai-gateway"))
        applyProviderAuthResult(
            state = state,
            authState = PiProviderAuthState(
                providerId = "cloudflare-ai-gateway",
                authMethod = ProviderAuthMethod.ApiKey,
                apiKey = "test-key",
                providerEnvironmentVariables = environment,
            ),
        )

        assertEquals("test-key", state.apiKey)
        assertEquals(environment, state.providerEnvironmentVariables)
        assertTrue(state.isAuthenticationConfigured())
    }

    @Test
    fun parseManualModelIdsAcceptsMultipleSeparators() {
        assertEquals(
            listOf("manual-a", "manual-b", "manual-c", "manual-d"),
            parseManualModelIds("manual-a\nmanual-b, manual-c; manual-d"),
        )
    }

    @Test
    fun newProviderKeepsManualModelIdEmptyAndCanStillBeSaved() {
        val state = ProviderFormState.fromConfig(null)
        state.applyProviderDefaults(PiProviderCatalog.resolve("openai"))
        state.apiKey = "test-key"

        assertEquals("", state.modelId)
        assertEquals("", state.buildConfig().modelId)
        assertEquals(SunshineLlmUserAgent, state.userAgent)
        assertTrue(state.isValid(emptySet()))
    }

    @Test
    fun buildConfigKeepsCustomUserAgentAndRestoresDefaultWhenBlank() {
        val state = ProviderFormState.fromConfig(null)

        state.userAgent = "  CustomAgent/4.0  "
        assertEquals("CustomAgent/4.0", state.buildConfig().userAgent)

        state.userAgent = ""
        assertEquals(SunshineLlmUserAgent, state.buildConfig().userAgent)
    }

    @Test
    fun oauthAccountLabelUsesTheBestAvailableIdentity() {
        assertEquals(
            "person@example.com",
            oauthAccountLabel("""{"email":"person@example.com","accountId":"account-1"}"""),
        )
        assertEquals(
            "account-1",
            oauthAccountLabel("""{"accountId":"account-1"}"""),
        )
        assertEquals("", oauthAccountLabel("not-json"))
    }

    @Test
    fun buildConfigRemovesManualModelWhenDeletedFromInput() {
        val state = ProviderFormState.fromConfig(
            LlmProviderConfig(
                providerId = "custom",
                name = "Custom",
                piProviderId = "openai-compatible",
                apiKey = "test-key",
                baseUrl = "https://api.example.com/v1",
                modelId = "manual-a",
                manualModelIds = listOf("manual-a", "manual-b"),
                cachedModels = listOf("fetched-a"),
                enabledModelIds = listOf("manual-a", "manual-b", "fetched-a"),
            )
        )

        state.modelId = "manual-b"
        val config = state.buildConfig()

        assertEquals(listOf("manual-b"), config.manualModelIds)
        assertEquals(listOf("fetched-a"), config.cachedModels)
        assertEquals(listOf("fetched-a", "manual-b"), config.availableModels())
        assertEquals(listOf("manual-b", "fetched-a"), config.enabledModelIds)
    }

    @Test
    fun buildConfigKeepsModelEnabledChanges() {
        val state = ProviderFormState.fromConfig(
            LlmProviderConfig(
                providerId = "custom",
                name = "Custom",
                piProviderId = "openai-compatible",
                apiKey = "test-key",
                baseUrl = "https://api.example.com/v1",
                modelId = "manual-a",
                manualModelIds = listOf("manual-a", "manual-b"),
                enabledModelIds = listOf("manual-a", "manual-b"),
            )
        )

        state.setModelEnabled("manual-b", false)

        assertEquals(listOf("manual-a"), state.buildConfig().enabledModelIds)
    }
}
