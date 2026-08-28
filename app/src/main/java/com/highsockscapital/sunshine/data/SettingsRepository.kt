package com.highsockscapital.sunshine.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "sunshine_settings")

class SettingsRepository(
    private val context: Context,
) {
    suspend fun loadModelCatalogCache(): Map<String, ModelCatalogInfo> = context.dataStore.data.first()
        .let { preferences -> parseModelCatalogCache(preferences[MODEL_CATALOG_CACHE_JSON].orEmpty()) }

    suspend fun saveModelCatalogCache(cache: Map<String, ModelCatalogInfo>) {
        context.dataStore.edit { preferences ->
            val merged = parseModelCatalogCache(preferences[MODEL_CATALOG_CACHE_JSON].orEmpty()) + cache
            preferences[MODEL_CATALOG_CACHE_JSON] = serializeModelCatalogCache(merged)
        }
    }

    suspend fun loadThinkingCatalogCache(): Map<String, List<String>> = context.dataStore.data.first()
        .let { preferences -> parseThinkingCatalogCache(preferences[THINKING_CATALOG_CACHE_JSON].orEmpty()).levels }

    suspend fun loadThinkingLevelMapsCache(): Map<String, Map<String, String>> = context.dataStore.data.first()
        .let { preferences -> parseThinkingCatalogCache(preferences[THINKING_CATALOG_CACHE_JSON].orEmpty()).clamps }

    suspend fun loadReasoningModelsCache(): Set<String> = context.dataStore.data.first()
        .let { preferences -> parseThinkingCatalogCache(preferences[THINKING_CATALOG_CACHE_JSON].orEmpty()).reasoningModels }

    suspend fun saveThinkingCatalogCache(
        cache: Map<String, List<String>>,
        levelMaps: Map<String, Map<String, String>> = emptyMap(),
        reasoningModels: Set<String> = emptySet(),
    ) {
        if (cache.isEmpty() && levelMaps.isEmpty() && reasoningModels.isEmpty()) return
        context.dataStore.edit { preferences ->
            val existing = parseThinkingCatalogCache(preferences[THINKING_CATALOG_CACHE_JSON].orEmpty())
            val mergedLevels = existing.levels + cache
            val mergedLevelMaps = (existing.clamps - cache.keys) + levelMaps
            val mergedReasoningModels = (existing.reasoningModels - cache.keys) + reasoningModels
            preferences[THINKING_CATALOG_CACHE_JSON] = serializeThinkingCatalogCache(
                mergedLevels,
                mergedLevelMaps,
                mergedReasoningModels,
            )
        }
    }
    suspend fun initializeLanguageIfNeeded() {
        val preferences = context.dataStore.data.first()
        if (preferences[LANGUAGE] == null) {
            context.dataStore.edit { it[LANGUAGE] = defaultAppLanguage().storageValue }
        }
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::parseSettings)

    private fun parseSettings(preferences: Preferences): AppSettings {
        val defaults = AppSettings()
        val storedWorkspaceMode = AgentWorkspaceMode.fromStorage(preferences[AGENT_WORKSPACE_MODE])
        val storedBaseUrl = preferences[BASE_URL] ?: defaults.baseUrl
        return AppSettings(
            piProviderId = preferences[PI_PROVIDER_ID]
                ?.takeIf(String::isNotBlank)
                ?: inferPiProviderIdFromBaseUrl(storedBaseUrl),
            providerConfigId = preferences[PROVIDER_CONFIG_ID].orEmpty(),
            providerAuthMethod = ProviderAuthMethod.fromStorage(preferences[PROVIDER_AUTH_METHOD]),
            apiKey = preferences[API_KEY].orEmpty(),
            oauthCredentialJson = preferences[OAUTH_CREDENTIAL_JSON].orEmpty(),
            providerEnvironmentVariables = parseProviderEnvironmentVariables(
                preferences[PROVIDER_ENVIRONMENT_VARIABLES]
                    ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() },
            ),
            baseUrl = storedBaseUrl,
            modelId = preferences[MODEL_ID] ?: defaults.modelId,
            userAgent = normalizeLlmUserAgent(preferences[USER_AGENT]),
            reasoningEffort = normalizeReasoningEffort(preferences[REASONING_EFFORT]),
            systemPrompt = preferences[SYSTEM_PROMPT] ?: defaults.systemPrompt,
            tavilyApiKey = preferences[TAVILY_API_KEY].orEmpty(),
            tavilyBaseUrl = normalizeTavilyBaseUrl(
                preferences[TAVILY_BASE_URL] ?: defaults.tavilyBaseUrl,
            ),
            llmInactivityReconnectTimeoutSeconds = normalizeLlmInactivityReconnectTimeoutSeconds(
                preferences[LLM_INACTIVITY_RECONNECT_TIMEOUT_SECONDS]
            ),
            keepTasksRunningInBackground = preferences[KEEP_TASKS_RUNNING_IN_BACKGROUND] ?: true,
            notifyOnTaskCompletion = preferences[NOTIFY_ON_TASK_COMPLETION] ?: true,
            agentWorkspaceMode = if (preferences[WORKSPACE_MODE_INITIALIZED] == true) {
                storedWorkspaceMode
            } else {
                defaults.agentWorkspaceMode
            },
            autoCleanOldCommandHistory =
                preferences[AUTO_CLEAN_OLD_COMMAND_HISTORY] ?: true,
            oldCommandHistoryRetentionHours = normalizeOldCommandHistoryRetentionHours(
                preferences[OLD_COMMAND_HISTORY_RETENTION_HOURS],
            ),
            termuxSetupCompleted = preferences[TERMUX_SETUP_COMPLETED] ?: false,
            termuxSetupNoticeDismissed = preferences[TERMUX_SETUP_NOTICE_DISMISSED] ?: false,
            termuxEnvironmentVariables = parseTermuxEnvironmentVariables(
                preferences[TERMUX_ENVIRONMENT_VARIABLES].orEmpty()
            ),
            enabledRuntimeIds = resolveEnabledRuntimeIds(
                rawValue = preferences[ENABLED_RUNTIME_IDS],
                termuxSetupCompleted = preferences[TERMUX_SETUP_COMPLETED] ?: false,
            ),
            defaultRuntimeId = resolveDefaultRuntimeId(
                rawValue = preferences[DEFAULT_RUNTIME_ID],
                enabledRuntimeIds = resolveEnabledRuntimeIds(
                    rawValue = preferences[ENABLED_RUNTIME_IDS],
                    termuxSetupCompleted = preferences[TERMUX_SETUP_COMPLETED] ?: false,
                ),
                termuxSetupCompleted = preferences[TERMUX_SETUP_COMPLETED] ?: false,
            ),
            agentModeAuthorizationEnabled = preferences[AGENT_MODE_AUTHORIZATION_ENABLED] ?: false,
            agentModeAuthorizationMethod = AgentModeAuthorizationMethod.fromStorage(
                preferences[AGENT_MODE_AUTHORIZATION_METHOD],
                defaultValue = defaultAgentModeAuthorizationMethod(context),
            ),
            language = AppLanguage.fromStorage(preferences[LANGUAGE]),
            themeMode = AppThemeMode.fromStorage(preferences[THEME_MODE]),
            defaultChatModelKey = preferences[DEFAULT_CHAT_MODEL_KEY].orEmpty(),
            defaultTitleModelKey = preferences[DEFAULT_TITLE_MODEL_KEY].orEmpty(),
            defaultNamingModelKey = preferences[DEFAULT_NAMING_MODEL_KEY].orEmpty(),
            defaultCompactingModelKey = preferences[DEFAULT_COMPACTING_MODEL_KEY].orEmpty(),
            autoCompactEnabled = preferences[AUTO_COMPACT_ENABLED] ?: true,
            autoCompactThresholdPercent = preferences[AUTO_COMPACT_THRESHOLD_PERCENT] ?: 85,
            defaultSelectedSkillIds = parseStringArray(
                preferences[DEFAULT_SELECTED_SKILL_IDS].orEmpty()
            ),
            subagentsSharedOpenRouterApiKey =
                preferences[SUBAGENTS_SHARED_OPENROUTER_API_KEY].orEmpty(),
            subagentConfigs = parseSubagentConfigs(
                preferences[SUBAGENT_CONFIGS_JSON].orEmpty()
            ),
            onboardingSeenVersion = preferences[ONBOARDING_SEEN_VERSION] ?: 0,
            onboardingCompletedVersion = preferences[ONBOARDING_COMPLETED_VERSION] ?: 0,
            privacyPolicyAccepted = preferences[PRIVACY_POLICY_ACCEPTED] ?: false,
            lastUpdateCheckAtMillis = preferences[LAST_UPDATE_CHECK_AT_MILLIS] ?: 0L,
        )
    }

    private fun parseSubagentConfigs(raw: String): Map<String, SubagentConfig> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            val out = mutableMapOf<String, SubagentConfig>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = json.optJSONObject(key) ?: continue
                out[key] = SubagentConfig(
                    enabled = entry.optBoolean("enabled", true),
                    modelId = entry.optString("modelId"),
                    apiKeyOverride = entry.optString("apiKeyOverride"),
                )
            }
            out.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun serializeSubagentConfigs(configs: Map<String, SubagentConfig>): String {
        if (configs.isEmpty()) return "{}"
        val json = org.json.JSONObject()
        configs.forEach { (name, config) ->
            json.put(
                name,
                org.json.JSONObject()
                    .put("enabled", config.enabled)
                    .put("modelId", config.modelId)
                    .put("apiKeyOverride", config.apiKeyOverride),
            )
        }
        return json.toString()
    }

    // ── Multi-Provider support ───────────────────────────────────────────────
    val providerConfigs: Flow<List<LlmProviderConfig>> = context.dataStore.data.map { preferences ->
        parseProviderConfigs(preferences[PROVIDER_CONFIGS].orEmpty())
    }

    suspend fun upsertProviderConfig(config: LlmProviderConfig) {
        context.dataStore.edit { prefs ->
            val current = parseProviderConfigs(prefs[PROVIDER_CONFIGS].orEmpty()).toMutableList()
            val existingIndex = current.indexOfFirst { it.id == config.id }
            val updatedConfig = config.copy(updatedAtMillis = System.currentTimeMillis())
            if (existingIndex >= 0) {
                current[existingIndex] = updatedConfig
            } else {
                current.add(updatedConfig)
            }
            prefs[PROVIDER_CONFIGS] = serializeProviderConfigs(current)
        }
    }

    suspend fun removeProviderConfig(id: String) {
        context.dataStore.edit { prefs ->
            val current = parseProviderConfigs(prefs[PROVIDER_CONFIGS].orEmpty())
            val updated = current.filter { it.id != id }
            prefs[PROVIDER_CONFIGS] = serializeProviderConfigs(updated)
        }
    }

    suspend fun updateProviderOAuthCredential(
        id: String,
        credentialJson: String,
    ) {
        if (id.isBlank() || credentialJson.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = parseProviderConfigs(prefs[PROVIDER_CONFIGS].orEmpty())
            val updated = current.map { config ->
                if (config.id == id) {
                    config.copy(
                        oauthCredentialJson = credentialJson,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                } else {
                    config
                }
            }
            prefs[PROVIDER_CONFIGS] = serializeProviderConfigs(updated)
            if (prefs[PROVIDER_CONFIG_ID] == id) {
                prefs[OAUTH_CREDENTIAL_JSON] = credentialJson
            }
        }
    }

    suspend fun setProviderEnabled(
        id: String,
        enabled: Boolean,
    ) {
        context.dataStore.edit { prefs ->
            val current = parseProviderConfigs(prefs[PROVIDER_CONFIGS].orEmpty())
            val currentPiProviderId = prefs[PI_PROVIDER_ID]
                ?.takeIf(String::isNotBlank)
                ?: inferPiProviderIdFromBaseUrl(
                    prefs[BASE_URL] ?: AppSettings().baseUrl,
                )
            val currentProviderConfigId = prefs[PROVIDER_CONFIG_ID].orEmpty()
            val currentApiKey = prefs[API_KEY].orEmpty()
            val currentBaseUrl = prefs[BASE_URL] ?: AppSettings().baseUrl
            val currentModelId = prefs[MODEL_ID] ?: AppSettings().modelId
            val toggledConfigWasCurrent = current
                .firstOrNull { it.id == id }
                ?.matchesStoredModel(
                    piProviderId = currentPiProviderId,
                    providerConfigId = currentProviderConfigId,
                    apiKey = currentApiKey,
                    baseUrl = currentBaseUrl,
                    modelId = currentModelId,
                ) == true
            val updated = current.map { config ->
                if (config.id == id) config.copy(isEnabled = enabled) else config
            }
            prefs[PROVIDER_CONFIGS] = serializeProviderConfigs(updated)

            val availableOptions = updated.availableModelOptions()
            val currentStillAvailable = availableOptions.any {
                it.matchesStoredModel(
                    piProviderId = currentPiProviderId,
                    providerConfigId = currentProviderConfigId,
                    apiKey = currentApiKey,
                    baseUrl = currentBaseUrl,
                    modelId = currentModelId,
                )
            }
            val fallbackOption = availableOptions.firstOrNull()
            if (!enabled && toggledConfigWasCurrent && !currentStillAvailable && fallbackOption != null) {
                prefs[PI_PROVIDER_ID] = fallbackOption.piProviderId
                prefs[PROVIDER_CONFIG_ID] = fallbackOption.providerConfigId
                prefs[PROVIDER_AUTH_METHOD] = fallbackOption.authMethod.storageValue
                prefs[API_KEY] = fallbackOption.apiKey
                prefs[OAUTH_CREDENTIAL_JSON] = fallbackOption.oauthCredentialJson
                prefs[PROVIDER_ENVIRONMENT_VARIABLES] = serializeProviderEnvironmentVariables(
                    fallbackOption.providerEnvironmentVariables,
                )
                prefs[BASE_URL] = fallbackOption.baseUrl
                prefs[MODEL_ID] = fallbackOption.modelId
                prefs[USER_AGENT] = normalizeLlmUserAgent(fallbackOption.userAgent)
            }
        }
    }

    suspend fun replaceImportedSettings(
        settings: AppSettings,
        providerConfigs: List<LlmProviderConfig>,
    ) {
        context.dataStore.edit {
            it[PI_PROVIDER_ID] = settings.piProviderId
            it[PROVIDER_CONFIG_ID] = settings.providerConfigId
            it[PROVIDER_AUTH_METHOD] = settings.providerAuthMethod.storageValue
            it[API_KEY] = settings.apiKey
            it[OAUTH_CREDENTIAL_JSON] = settings.oauthCredentialJson
            it[PROVIDER_ENVIRONMENT_VARIABLES] = serializeProviderEnvironmentVariables(
                settings.providerEnvironmentVariables,
            )
            it[BASE_URL] = settings.baseUrl
            it[MODEL_ID] = settings.modelId
            it[USER_AGENT] = normalizeLlmUserAgent(settings.userAgent)
            it[REASONING_EFFORT] = normalizeReasoningEffort(settings.reasoningEffort)
            it[SYSTEM_PROMPT] = settings.systemPrompt
            it[TAVILY_API_KEY] = settings.tavilyApiKey
            it[TAVILY_BASE_URL] = normalizeTavilyBaseUrl(settings.tavilyBaseUrl)
            it[LLM_INACTIVITY_RECONNECT_TIMEOUT_SECONDS] =
                normalizeLlmInactivityReconnectTimeoutSeconds(
                    settings.llmInactivityReconnectTimeoutSeconds
                )
            it[KEEP_TASKS_RUNNING_IN_BACKGROUND] = settings.keepTasksRunningInBackground
            it[NOTIFY_ON_TASK_COMPLETION] = settings.notifyOnTaskCompletion
            it[AGENT_WORKSPACE_MODE] = settings.agentWorkspaceMode.storageValue
            it[WORKSPACE_MODE_INITIALIZED] = true
            it[AUTO_CLEAN_OLD_COMMAND_HISTORY] = settings.autoCleanOldCommandHistory
            it[OLD_COMMAND_HISTORY_RETENTION_HOURS] =
                normalizeOldCommandHistoryRetentionHours(settings.oldCommandHistoryRetentionHours)
            it[TERMUX_SETUP_COMPLETED] = settings.termuxSetupCompleted
            it[TERMUX_SETUP_NOTICE_DISMISSED] = settings.termuxSetupNoticeDismissed
            it[TERMUX_ENVIRONMENT_VARIABLES] =
                serializeTermuxEnvironmentVariables(settings.termuxEnvironmentVariables)
            it[ENABLED_RUNTIME_IDS] = serializeRuntimeIds(settings.enabledRuntimeIds)
            settings.defaultRuntimeId?.let { runtimeId ->
                it[DEFAULT_RUNTIME_ID] = runtimeId.storageValue
            } ?: it.remove(DEFAULT_RUNTIME_ID)
            it[AGENT_MODE_AUTHORIZATION_ENABLED] = settings.agentModeAuthorizationEnabled
            it[AGENT_MODE_AUTHORIZATION_METHOD] = settings.agentModeAuthorizationMethod.storageValue
            it[LANGUAGE] = settings.language.storageValue
            it[THEME_MODE] = settings.themeMode.storageValue
            it[DEFAULT_CHAT_MODEL_KEY] = settings.defaultChatModelKey
            it[DEFAULT_TITLE_MODEL_KEY] = settings.defaultTitleModelKey
            it[DEFAULT_NAMING_MODEL_KEY] = settings.defaultNamingModelKey
            it[DEFAULT_COMPACTING_MODEL_KEY] = settings.defaultCompactingModelKey
            it[AUTO_COMPACT_ENABLED] = settings.autoCompactEnabled
            it[AUTO_COMPACT_THRESHOLD_PERCENT] = settings.autoCompactThresholdPercent
            it[DEFAULT_SELECTED_SKILL_IDS] = serializeStringArray(settings.defaultSelectedSkillIds)
            it.remove(BASIC_FUNCTION_CALLING_COMPATIBILITY_MODE)
            it.remove(UNSUPPORTED_PARALLEL_TOOL_CALL_PROVIDER_KEYS)
            it[SUBAGENTS_SHARED_OPENROUTER_API_KEY] = settings.subagentsSharedOpenRouterApiKey
            it[SUBAGENT_CONFIGS_JSON] = serializeSubagentConfigs(settings.subagentConfigs)
            it[ONBOARDING_SEEN_VERSION] = settings.onboardingSeenVersion
            it[ONBOARDING_COMPLETED_VERSION] = settings.onboardingCompletedVersion
            it[PRIVACY_POLICY_ACCEPTED] =
                (it[PRIVACY_POLICY_ACCEPTED] ?: false) || settings.privacyPolicyAccepted
            it[LAST_UPDATE_CHECK_AT_MILLIS] = settings.lastUpdateCheckAtMillis
            it[PROVIDER_CONFIGS] = serializeProviderConfigs(providerConfigs)
        }
    }

    suspend fun updateApiKey(value: String) {
        context.dataStore.edit { it[API_KEY] = value }
    }

    suspend fun updateBaseUrl(value: String) {
        context.dataStore.edit { it[BASE_URL] = value }
    }

    suspend fun updateModelId(value: String) {
        context.dataStore.edit { it[MODEL_ID] = value }
    }

    suspend fun updateSystemPrompt(value: String) {
        context.dataStore.edit { it[SYSTEM_PROMPT] = value }
    }

    suspend fun isTermuxSetupCompleted(): Boolean =
        context.dataStore.data.first()[TERMUX_SETUP_COMPLETED] ?: false

    /**
     * Persists only the Termux completion flag and enabled runtimes. A full
     * settings-object write is unsafe before the settings flow has emitted,
     * because it would overwrite every stored key with defaults.
     */
    suspend fun markTermuxSetupCompleted() {
        context.dataStore.edit { prefs ->
            prefs[TERMUX_SETUP_COMPLETED] = true
            val enabled =
                parseRuntimeIds(prefs[ENABLED_RUNTIME_IDS].orEmpty()) + LocalRuntimeId.Termux
            prefs[ENABLED_RUNTIME_IDS] = serializeRuntimeIds(enabled)
        }
    }

    suspend fun updateLanguage(language: AppLanguage) {
        context.dataStore.edit { it[LANGUAGE] = language.storageValue }
    }

    suspend fun updateThemeMode(themeMode: AppThemeMode) {
        context.dataStore.edit { it[THEME_MODE] = themeMode.storageValue }
    }

    private fun writeAppSettings(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        settings: AppSettings,
    ) {
            prefs[PI_PROVIDER_ID] = settings.piProviderId
            prefs[PROVIDER_CONFIG_ID] = settings.providerConfigId
            prefs[PROVIDER_AUTH_METHOD] = settings.providerAuthMethod.storageValue
            prefs[API_KEY] = settings.apiKey
            prefs[OAUTH_CREDENTIAL_JSON] = settings.oauthCredentialJson
            prefs[PROVIDER_ENVIRONMENT_VARIABLES] = serializeProviderEnvironmentVariables(
                settings.providerEnvironmentVariables,
            )
            prefs[BASE_URL] = settings.baseUrl
            prefs[MODEL_ID] = settings.modelId
            prefs[USER_AGENT] = normalizeLlmUserAgent(settings.userAgent)
            prefs[REASONING_EFFORT] = normalizeReasoningEffort(settings.reasoningEffort)
            prefs[SYSTEM_PROMPT] = settings.systemPrompt
            prefs[TAVILY_API_KEY] = settings.tavilyApiKey
            prefs[TAVILY_BASE_URL] = normalizeTavilyBaseUrl(settings.tavilyBaseUrl)
            prefs[LLM_INACTIVITY_RECONNECT_TIMEOUT_SECONDS] =
                normalizeLlmInactivityReconnectTimeoutSeconds(
                    settings.llmInactivityReconnectTimeoutSeconds
                )
            prefs[KEEP_TASKS_RUNNING_IN_BACKGROUND] = settings.keepTasksRunningInBackground
            prefs[NOTIFY_ON_TASK_COMPLETION] = settings.notifyOnTaskCompletion
            prefs[AGENT_WORKSPACE_MODE] = settings.agentWorkspaceMode.storageValue
            prefs[WORKSPACE_MODE_INITIALIZED] = true
            prefs[AUTO_CLEAN_OLD_COMMAND_HISTORY] = settings.autoCleanOldCommandHistory
            prefs[OLD_COMMAND_HISTORY_RETENTION_HOURS] =
                normalizeOldCommandHistoryRetentionHours(settings.oldCommandHistoryRetentionHours)
            prefs[TERMUX_SETUP_COMPLETED] = settings.termuxSetupCompleted
            prefs[TERMUX_SETUP_NOTICE_DISMISSED] = settings.termuxSetupNoticeDismissed
            prefs[TERMUX_ENVIRONMENT_VARIABLES] =
                serializeTermuxEnvironmentVariables(settings.termuxEnvironmentVariables)
            prefs[ENABLED_RUNTIME_IDS] = serializeRuntimeIds(settings.enabledRuntimeIds)
            settings.defaultRuntimeId?.let { runtimeId ->
                prefs[DEFAULT_RUNTIME_ID] = runtimeId.storageValue
            } ?: prefs.remove(DEFAULT_RUNTIME_ID)
            prefs[AGENT_MODE_AUTHORIZATION_ENABLED] = settings.agentModeAuthorizationEnabled
            prefs[AGENT_MODE_AUTHORIZATION_METHOD] = settings.agentModeAuthorizationMethod.storageValue
            prefs[LANGUAGE] = settings.language.storageValue
            prefs[THEME_MODE] = settings.themeMode.storageValue
            prefs[DEFAULT_CHAT_MODEL_KEY] = settings.defaultChatModelKey
            prefs[DEFAULT_TITLE_MODEL_KEY] = settings.defaultTitleModelKey
            prefs[DEFAULT_NAMING_MODEL_KEY] = settings.defaultNamingModelKey
            prefs[DEFAULT_COMPACTING_MODEL_KEY] = settings.defaultCompactingModelKey
            prefs[AUTO_COMPACT_ENABLED] = settings.autoCompactEnabled
            prefs[AUTO_COMPACT_THRESHOLD_PERCENT] = settings.autoCompactThresholdPercent
            prefs[DEFAULT_SELECTED_SKILL_IDS] = serializeStringArray(settings.defaultSelectedSkillIds)
            prefs.remove(BASIC_FUNCTION_CALLING_COMPATIBILITY_MODE)
            prefs.remove(UNSUPPORTED_PARALLEL_TOOL_CALL_PROVIDER_KEYS)
            prefs[SUBAGENTS_SHARED_OPENROUTER_API_KEY] = settings.subagentsSharedOpenRouterApiKey
            prefs[SUBAGENT_CONFIGS_JSON] = serializeSubagentConfigs(settings.subagentConfigs)
            prefs[PRIVACY_POLICY_ACCEPTED] =
                (prefs[PRIVACY_POLICY_ACCEPTED] ?: false) || settings.privacyPolicyAccepted
            prefs[LAST_UPDATE_CHECK_AT_MILLIS] = settings.lastUpdateCheckAtMillis
        }

    suspend fun updateSettings(settings: AppSettings) {
        context.dataStore.edit { prefs -> writeAppSettings(prefs, settings) }
    }

    /**
     * Transactional read-modify-write: the transform sees the latest stored
     * settings, so concurrent writers can never clobber each other's fields
     * (e.g. reverting a freshly saved custom system prompt).
     */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings =
        context.dataStore.edit { prefs ->
            writeAppSettings(prefs, transform(parseSettings(prefs)))
        }.let(::parseSettings)

    suspend fun updatePrivacyPolicyAccepted(accepted: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PRIVACY_POLICY_ACCEPTED] = accepted
        }
    }

    suspend fun updateOnboardingSeenVersion(version: Int) {
        context.dataStore.edit { prefs ->
            prefs[ONBOARDING_SEEN_VERSION] = version
        }
    }

    suspend fun updateOnboardingCompletedVersion(version: Int) {
        context.dataStore.edit { prefs ->
            prefs[ONBOARDING_COMPLETED_VERSION] = version
        }
    }

    suspend fun updateLastUpdateCheckAtMillis(value: Long) {
        context.dataStore.edit { prefs ->
            prefs[LAST_UPDATE_CHECK_AT_MILLIS] = value
        }
    }

    suspend fun updateDefaultSelectedSkillIds(skillIds: List<String>) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_SELECTED_SKILL_IDS] = serializeStringArray(skillIds)
        }
    }

    suspend fun isWorkspaceModeInitialized(): Boolean =
        context.dataStore.data.map { preferences ->
            preferences[WORKSPACE_MODE_INITIALIZED] ?: false
        }.first()

    private companion object {
        val PI_PROVIDER_ID = stringPreferencesKey("pi_provider_id")
        val PROVIDER_CONFIG_ID = stringPreferencesKey("provider_config_id")
        val PROVIDER_AUTH_METHOD = stringPreferencesKey("provider_auth_method")
        val API_KEY = stringPreferencesKey("api_key")
        val OAUTH_CREDENTIAL_JSON = stringPreferencesKey("oauth_credential_json")
        val PROVIDER_ENVIRONMENT_VARIABLES = stringPreferencesKey("provider_environment_variables")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL_ID = stringPreferencesKey("model_id")
        val USER_AGENT = stringPreferencesKey("user_agent")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val TAVILY_API_KEY = stringPreferencesKey("tavily_api_key")
        val TAVILY_BASE_URL = stringPreferencesKey("tavily_base_url")
        val LLM_INACTIVITY_RECONNECT_TIMEOUT_SECONDS =
            intPreferencesKey("llm_inactivity_reconnect_timeout_seconds")
        val KEEP_TASKS_RUNNING_IN_BACKGROUND =
            booleanPreferencesKey("keep_tasks_running_in_background")
        val NOTIFY_ON_TASK_COMPLETION =
            booleanPreferencesKey("notify_on_task_completion")
        val AGENT_WORKSPACE_MODE = stringPreferencesKey("agent_workspace_mode")
        val WORKSPACE_MODE_INITIALIZED =
            booleanPreferencesKey("workspace_mode_initialized")
        val AUTO_CLEAN_OLD_COMMAND_HISTORY =
            booleanPreferencesKey("auto_clean_old_command_history")
        val OLD_COMMAND_HISTORY_RETENTION_HOURS =
            intPreferencesKey("old_command_history_retention_hours")
        val TERMUX_SETUP_COMPLETED =
            booleanPreferencesKey("termux_setup_completed")
        val TERMUX_SETUP_NOTICE_DISMISSED =
            booleanPreferencesKey("termux_setup_notice_dismissed")
        val TERMUX_ENVIRONMENT_VARIABLES =
            stringPreferencesKey("termux_environment_variables")
        val ENABLED_RUNTIME_IDS =
            stringPreferencesKey("enabled_runtime_ids")
        val DEFAULT_RUNTIME_ID =
            stringPreferencesKey("default_runtime_id")
        val AGENT_MODE_AUTHORIZATION_ENABLED =
            booleanPreferencesKey("agent_mode_authorization_enabled")
        val AGENT_MODE_AUTHORIZATION_METHOD =
            stringPreferencesKey("agent_mode_authorization_method")
        val LANGUAGE = stringPreferencesKey("language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_CHAT_MODEL_KEY = stringPreferencesKey("default_chat_model_key")
        val DEFAULT_TITLE_MODEL_KEY = stringPreferencesKey("default_title_model_key")
        val DEFAULT_NAMING_MODEL_KEY = stringPreferencesKey("default_naming_model_key")
        val DEFAULT_COMPACTING_MODEL_KEY = stringPreferencesKey("default_compacting_model_key")
        val AUTO_COMPACT_ENABLED = booleanPreferencesKey("auto_compact_enabled")
        val AUTO_COMPACT_THRESHOLD_PERCENT = intPreferencesKey("auto_compact_threshold_percent")
        val MODEL_CATALOG_CACHE_JSON = stringPreferencesKey("model_catalog_cache_json")
        val THINKING_CATALOG_CACHE_JSON = stringPreferencesKey("thinking_catalog_cache_json")
        val DEFAULT_SELECTED_SKILL_IDS = stringPreferencesKey("default_selected_skill_ids")
        val SUBAGENTS_SHARED_OPENROUTER_API_KEY =
            stringPreferencesKey("subagents_shared_openrouter_api_key")
        val SUBAGENT_CONFIGS_JSON = stringPreferencesKey("subagent_configs_json")
        val UNSUPPORTED_PARALLEL_TOOL_CALL_PROVIDER_KEYS =
            stringPreferencesKey("unsupported_parallel_tool_call_provider_keys")
        val BASIC_FUNCTION_CALLING_COMPATIBILITY_MODE =
            booleanPreferencesKey("basic_function_calling_compatibility_mode")
        val PROVIDER_CONFIGS = stringPreferencesKey("provider_configs")
        val ONBOARDING_SEEN_VERSION = intPreferencesKey("onboarding_seen_version")
        val ONBOARDING_COMPLETED_VERSION = intPreferencesKey("onboarding_completed_version")
        val PRIVACY_POLICY_ACCEPTED = booleanPreferencesKey("privacy_policy_accepted")
        val LAST_UPDATE_CHECK_AT_MILLIS = longPreferencesKey("last_update_check_at_millis")
    }
}

private fun parseStringArray(rawValue: String): List<String> {
    if (rawValue.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(rawValue)
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct()
    }.getOrDefault(emptyList())
}

private fun serializeStringArray(values: List<String>): String =
    JSONArray().apply {
        values.map(String::trim).filter(String::isNotBlank).distinct().forEach(::put)
    }.toString()

private fun LlmProviderConfig.matchesStoredModel(
    piProviderId: String,
    providerConfigId: String,
    apiKey: String,
    baseUrl: String,
    modelId: String,
): Boolean =
    (id == providerConfigId || this.piProviderId == piProviderId) &&
        this.apiKey.trim() == apiKey.trim() &&
        this.baseUrl.trim() == baseUrl.trim() &&
        enabledModels().contains(modelId.trim())

private fun ProviderModelOption.matchesStoredModel(
    piProviderId: String,
    providerConfigId: String,
    apiKey: String,
    baseUrl: String,
    modelId: String,
): Boolean =
    (this.providerConfigId == providerConfigId || this.piProviderId == piProviderId) &&
        this.apiKey.trim() == apiKey.trim() &&
        this.baseUrl.trim() == baseUrl.trim() &&
        this.modelId.trim() == modelId.trim()

private fun serializeProviderEnvironmentVariables(
    variables: List<PiProviderEnvironmentVariable>,
): String = JSONArray().apply {
    variables
        .mapNotNull { variable ->
            variable.name.trim().takeIf(String::isNotBlank)?.let { name ->
                PiProviderEnvironmentVariable(name, variable.value)
            }
        }
        .distinctBy { it.name.uppercase() }
        .forEach { variable ->
            put(
                JSONObject()
                    .put("name", variable.name)
                    .put("value", variable.value)
            )
        }
}.toString()

private val TermuxEnvironmentVariableNamePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

fun normalizeTermuxEnvironmentVariables(
    variables: List<TermuxEnvironmentVariable>,
): List<TermuxEnvironmentVariable> =
    variables
        .mapNotNull { variable ->
            val name = variable.name.trim()
            if (!TermuxEnvironmentVariableNamePattern.matches(name)) {
                null
            } else {
                TermuxEnvironmentVariable(name = name, value = variable.value)
            }
        }
        .distinctBy { it.name }

private fun resolveEnabledRuntimeIds(
    rawValue: String?,
    termuxSetupCompleted: Boolean,
): Set<LocalRuntimeId> {
    val stored = parseRuntimeIds(rawValue.orEmpty())
    if (stored.isNotEmpty() || rawValue != null) return stored
    return buildSet {
        if (termuxSetupCompleted) add(LocalRuntimeId.Termux)
    }
}

private fun resolveDefaultRuntimeId(
    rawValue: String?,
    enabledRuntimeIds: Set<LocalRuntimeId>,
    termuxSetupCompleted: Boolean,
): LocalRuntimeId? {
    LocalRuntimeId.fromStorage(rawValue)?.let { runtimeId ->
        if (runtimeId in enabledRuntimeIds) return runtimeId
    }
    return when {
        termuxSetupCompleted && LocalRuntimeId.Termux in enabledRuntimeIds -> LocalRuntimeId.Termux
        else -> enabledRuntimeIds.firstOrNull()
    }
}

private fun parseRuntimeIds(rawValue: String): Set<LocalRuntimeId> {
    if (rawValue.isBlank()) return emptySet()
    return runCatching {
        val array = JSONArray(rawValue)
        buildSet {
            for (index in 0 until array.length()) {
                LocalRuntimeId.fromStorage(array.optString(index))?.let(::add)
            }
        }
    }.getOrDefault(emptySet())
}

private fun serializeRuntimeIds(runtimeIds: Set<LocalRuntimeId>): String =
    JSONArray().apply {
        runtimeIds.sortedBy { it.storageValue }.forEach { put(it.storageValue) }
    }.toString()

private fun parseTermuxEnvironmentVariables(rawValue: String): List<TermuxEnvironmentVariable> {
    if (rawValue.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(rawValue)
        normalizeTermuxEnvironmentVariables(
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        TermuxEnvironmentVariable(
                            name = item.optString("name"),
                            value = item.optString("value"),
                        )
                    )
                }
            }
        )
    }.getOrDefault(emptyList())
}

private fun serializeTermuxEnvironmentVariables(
    variables: List<TermuxEnvironmentVariable>,
): String =
    JSONArray().apply {
        normalizeTermuxEnvironmentVariables(variables).forEach { variable ->
            put(
                JSONObject().apply {
                    put("name", variable.name)
                    put("value", variable.value)
                }
            )
        }
    }.toString()

private val ShizukuManagerPackages = listOf(
    "moe.shizuku.privileged.api",
    "moe.shizuku.manager",
)

private fun defaultAgentModeAuthorizationMethod(
    context: Context,
): AgentModeAuthorizationMethod =
    if (isAnyPackageInstalled(context, ShizukuManagerPackages)) {
        AgentModeAuthorizationMethod.Shizuku
    } else {
        AgentModeAuthorizationMethod.Root
    }

private fun isAnyPackageInstalled(
    context: Context,
    packageNames: List<String>,
): Boolean {
    val packageManager = context.packageManager
    return packageNames.any { packageName ->
        runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
    }
}

private fun serializeModelCatalogCache(cache: Map<String, ModelCatalogInfo>): String = JSONArray().apply {
    cache.forEach { (key, info) ->
        put(JSONObject().apply {
            put("key", key)
            put("displayName", info.displayName)
            put("labId", info.labId)
            put("labName", info.labName)
            put("labLogoUrl", info.labLogoUrl)
            put("labLogoPathData", JSONArray(info.labLogoPathData))
            put("labLogoViewportWidth", info.labLogoViewportWidth)
            put("labLogoViewportHeight", info.labLogoViewportHeight)
        })
    }
}.toString()

private fun parseModelCatalogCache(raw: String): Map<String, ModelCatalogInfo> = runCatching {
    val array = JSONArray(raw)
    buildMap {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val key = item.optString("key").takeIf(String::isNotBlank) ?: continue
            val paths = item.optJSONArray("labLogoPathData")?.let { values ->
                buildList { for (i in 0 until values.length()) add(values.optString(i)) }
            }.orEmpty()
            put(key, ModelCatalogInfo(
                displayName = item.optString("displayName"), labId = item.optString("labId"),
                labName = item.optString("labName"), labLogoUrl = item.optString("labLogoUrl"),
                labLogoPathData = paths,
                labLogoViewportWidth = item.optDouble("labLogoViewportWidth", 40.0).toFloat(),
                labLogoViewportHeight = item.optDouble("labLogoViewportHeight", 40.0).toFloat(),
            ))
        }
    }
}.getOrDefault(emptyMap())

private fun serializeThinkingCatalogCache(
    levels: Map<String, List<String>>,
    clamps: Map<String, Map<String, String>> = emptyMap(),
    reasoningModels: Set<String> = emptySet(),
): String = JSONArray().apply {
    val allKeys = (levels.keys + clamps.keys + reasoningModels).distinct()
    allKeys.forEach { key ->
        put(JSONObject().apply {
            put("key", key)
            put("reasoning", key in reasoningModels)
            levels[key]?.let { put("levels", JSONArray(it)) }
            clamps[key]?.let { clampMap ->
                if (clampMap.isNotEmpty()) {
                    put("clamps", JSONObject().apply {
                        clampMap.forEach { (k, v) -> put(k, v) }
                    })
                }
            }
        })
    }
}.toString()

private data class ThinkingCatalogCache(
    val levels: Map<String, List<String>> = emptyMap(),
    val clamps: Map<String, Map<String, String>> = emptyMap(),
    val reasoningModels: Set<String> = emptySet(),
)

private fun parseThinkingCatalogCache(raw: String): ThinkingCatalogCache = runCatching {
    val array = JSONArray(raw)
    val levelsMap = mutableMapOf<String, List<String>>()
    val clampsMap = mutableMapOf<String, Map<String, String>>()
    val reasoningModels = mutableSetOf<String>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val key = item.optString("key").takeIf(String::isNotBlank) ?: continue
        if (item.optBoolean("reasoning")) reasoningModels += key
        item.optJSONArray("levels")?.let { values ->
            val levels = buildList {
                for (i in 0 until values.length()) {
                    values.optString(i).trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }
            levelsMap[key] = levels
        }
        item.optJSONObject("clamps")?.let { clampsObj ->
            val clamps = buildMap {
                for (k in clampsObj.keys()) {
                    clampsObj.optString(k).takeIf(String::isNotBlank)?.let { put(k, it) }
                }
            }
            if (clamps.isNotEmpty()) clampsMap[key] = clamps
        }
    }
    ThinkingCatalogCache(levelsMap, clampsMap, reasoningModels)
}.getOrDefault(ThinkingCatalogCache())
