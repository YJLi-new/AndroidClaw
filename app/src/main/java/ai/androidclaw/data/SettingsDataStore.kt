package ai.androidclaw.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID

private val Context.settingsDataStore by preferencesDataStore(name = "androidclaw_settings")

data class MemorySettingsSnapshot(
    val enabled: Boolean = false,
    val installUserId: String = "",
)

class SettingsDataStore(
    private val context: Context,
) {
    private val providerTypeKey = stringPreferencesKey("provider_type")
    private val themePreferenceKey = stringPreferencesKey("theme_preference")
    private val memoryEnabledKey = booleanPreferencesKey("memory_enabled")
    private val memoryInstallUserIdKey = stringPreferencesKey("memory_install_user_id")
    private val legacyOpenAiBaseUrlKey = stringPreferencesKey("openai_base_url")
    private val legacyOpenAiModelIdKey = stringPreferencesKey("openai_model_id")
    private val legacyOpenAiTimeoutSecondsKey = intPreferencesKey("openai_timeout_seconds")

    val settings: Flow<ProviderSettingsSnapshot> =
        context.settingsDataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }.map { preferences ->
                ProviderSettingsSnapshot(
                    providerType = ProviderType.fromStorage(preferences[providerTypeKey]),
                    providerConfigs =
                        ProviderType.configurableProviders.associateWith { providerType ->
                            ProviderEndpointSettings(
                                baseUrl = readBaseUrl(preferences, providerType),
                                modelId = readModelId(preferences, providerType),
                                timeoutSeconds = readTimeoutSeconds(preferences, providerType),
                            )
                        },
                )
            }

    val themePreference: Flow<ThemePreference> =
        context.settingsDataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }.map { preferences ->
                ThemePreference.fromStorage(preferences[themePreferenceKey])
            }

    val memorySettings: Flow<MemorySettingsSnapshot> =
        context.settingsDataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }.map { preferences ->
                MemorySettingsSnapshot(
                    enabled = preferences[memoryEnabledKey] ?: false,
                    installUserId = preferences[memoryInstallUserIdKey].orEmpty(),
                )
            }

    suspend fun saveProviderSettings(settings: ProviderSettingsSnapshot) {
        context.settingsDataStore.edit { preferences ->
            preferences[providerTypeKey] = settings.providerType.storageValue
            ProviderType.configurableProviders.forEach { providerType ->
                val providerSettings = settings.endpointSettings(providerType)
                val timeoutSeconds =
                    normalizeProviderTimeoutSeconds(
                        timeoutSeconds = providerSettings.timeoutSeconds,
                        fallbackSeconds = providerType.defaultTimeoutSeconds,
                    )
                preferences[baseUrlKey(providerType)] = providerSettings.baseUrl.trim()
                preferences[modelIdKey(providerType)] = providerSettings.modelId.trim()
                preferences[timeoutSecondsKey(providerType)] = timeoutSeconds
                if (providerType == ProviderType.OpenAiCompatible) {
                    preferences[legacyOpenAiBaseUrlKey] = providerSettings.baseUrl.trim()
                    preferences[legacyOpenAiModelIdKey] = providerSettings.modelId.trim()
                    preferences[legacyOpenAiTimeoutSecondsKey] = timeoutSeconds
                }
            }
        }
    }

    suspend fun setProviderType(providerType: ProviderType) {
        context.settingsDataStore.edit { preferences ->
            preferences[providerTypeKey] = providerType.storageValue
        }
    }

    suspend fun setThemePreference(themePreference: ThemePreference) {
        context.settingsDataStore.edit { preferences ->
            preferences[themePreferenceKey] = themePreference.storageValue
        }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[memoryEnabledKey] = enabled
            if (enabled && preferences[memoryInstallUserIdKey].isNullOrBlank()) {
                preferences[memoryInstallUserIdKey] = UUID.randomUUID().toString()
            }
        }
    }

    suspend fun memorySettingsSnapshot(): MemorySettingsSnapshot {
        val preferences = context.settingsDataStore.data.first()
        val existingUserId = preferences[memoryInstallUserIdKey]?.takeIf { it.isNotBlank() }
        if (existingUserId != null) {
            return MemorySettingsSnapshot(
                enabled = preferences[memoryEnabledKey] ?: false,
                installUserId = existingUserId,
            )
        }
        if (preferences[memoryEnabledKey] != true) {
            return MemorySettingsSnapshot(
                enabled = false,
                installUserId = "",
            )
        }

        val createdUserId = UUID.randomUUID().toString()
        context.settingsDataStore.edit { editablePreferences ->
            editablePreferences[memoryInstallUserIdKey] = createdUserId
        }
        return MemorySettingsSnapshot(
            enabled = preferences[memoryEnabledKey] ?: false,
            installUserId = createdUserId,
        )
    }

    private fun readBaseUrl(
        preferences: Preferences,
        providerType: ProviderType,
    ): String =
        when (providerType) {
            ProviderType.OpenAiCompatible -> {
                preferences[baseUrlKey(providerType)]
                    ?: preferences[legacyOpenAiBaseUrlKey]
                    ?: providerType.defaultBaseUrl
            }

            else -> preferences[baseUrlKey(providerType)] ?: providerType.defaultBaseUrl
        }

    private fun readModelId(
        preferences: Preferences,
        providerType: ProviderType,
    ): String =
        when (providerType) {
            ProviderType.OpenAiCompatible -> {
                preferences[modelIdKey(providerType)]
                    ?: preferences[legacyOpenAiModelIdKey]
                    ?: providerType.defaultModelId
            }

            ProviderType.OpenAiCodex -> {
                val storedModelId =
                    preferences[modelIdKey(providerType)]
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                if (storedModelId == OPENAI_CODEX_LEGACY_DEFAULT_MODEL_ID) {
                    OPENAI_CODEX_DEFAULT_MODEL_ID
                } else {
                    storedModelId ?: providerType.defaultModelId
                }
            }

            else -> preferences[modelIdKey(providerType)] ?: providerType.defaultModelId
        }

    private fun readTimeoutSeconds(
        preferences: Preferences,
        providerType: ProviderType,
    ): Int {
        val storedValue =
            when (providerType) {
                ProviderType.OpenAiCompatible -> {
                    preferences[timeoutSecondsKey(providerType)]
                        ?: preferences[legacyOpenAiTimeoutSecondsKey]
                        ?: providerType.defaultTimeoutSeconds
                }

                else -> preferences[timeoutSecondsKey(providerType)] ?: providerType.defaultTimeoutSeconds
            }
        return normalizeProviderTimeoutSeconds(
            timeoutSeconds = storedValue,
            fallbackSeconds = providerType.defaultTimeoutSeconds,
        )
    }

    private fun baseUrlKey(providerType: ProviderType) = stringPreferencesKey("provider_${providerType.storageValue}_base_url")

    private fun modelIdKey(providerType: ProviderType) = stringPreferencesKey("provider_${providerType.storageValue}_model_id")

    private fun timeoutSecondsKey(providerType: ProviderType) = intPreferencesKey("provider_${providerType.storageValue}_timeout_seconds")
}
