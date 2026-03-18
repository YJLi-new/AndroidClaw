package ai.androidclaw.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "androidclaw_settings")

class SettingsDataStore(
    private val context: Context,
) {
    private val providerTypeKey = stringPreferencesKey("provider_type")
    private val themePreferenceKey = stringPreferencesKey("theme_preference")
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

    suspend fun saveProviderSettings(settings: ProviderSettingsSnapshot) {
        context.settingsDataStore.edit { preferences ->
            preferences[providerTypeKey] = settings.providerType.storageValue
            ProviderType.configurableProviders.forEach { providerType ->
                val providerSettings = settings.endpointSettings(providerType)
                preferences[baseUrlKey(providerType)] = providerSettings.baseUrl.trim()
                preferences[modelIdKey(providerType)] = providerSettings.modelId.trim()
                preferences[timeoutSecondsKey(providerType)] = providerSettings.timeoutSeconds
                if (providerType == ProviderType.OpenAiCompatible) {
                    preferences[legacyOpenAiBaseUrlKey] = providerSettings.baseUrl.trim()
                    preferences[legacyOpenAiModelIdKey] = providerSettings.modelId.trim()
                    preferences[legacyOpenAiTimeoutSecondsKey] = providerSettings.timeoutSeconds
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
                    ?: ""
            }

            else -> preferences[modelIdKey(providerType)] ?: ""
        }

    private fun readTimeoutSeconds(
        preferences: Preferences,
        providerType: ProviderType,
    ): Int =
        when (providerType) {
            ProviderType.OpenAiCompatible -> {
                preferences[timeoutSecondsKey(providerType)]
                    ?: preferences[legacyOpenAiTimeoutSecondsKey]
                    ?: providerType.defaultTimeoutSeconds
            }

            else -> preferences[timeoutSecondsKey(providerType)] ?: providerType.defaultTimeoutSeconds
        }

    private fun baseUrlKey(providerType: ProviderType) = stringPreferencesKey("provider_${providerType.storageValue}_base_url")

    private fun modelIdKey(providerType: ProviderType) = stringPreferencesKey("provider_${providerType.storageValue}_model_id")

    private fun timeoutSecondsKey(providerType: ProviderType) = intPreferencesKey("provider_${providerType.storageValue}_timeout_seconds")
}
