package ai.androidclaw.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "androidclaw_settings")

class SettingsDataStore(
    private val context: Context,
) {
    private val providerTypeKey = stringPreferencesKey("provider_type")
    private val openAiBaseUrlKey = stringPreferencesKey("openai_base_url")
    private val openAiModelIdKey = stringPreferencesKey("openai_model_id")
    private val openAiTimeoutSecondsKey = intPreferencesKey("openai_timeout_seconds")

    val settings: Flow<ProviderSettingsSnapshot> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            ProviderSettingsSnapshot(
                providerType = ProviderType.fromStorage(preferences[providerTypeKey]),
                openAiBaseUrl = preferences[openAiBaseUrlKey] ?: OPENAI_DEFAULT_BASE_URL,
                openAiModelId = preferences[openAiModelIdKey] ?: "",
                openAiTimeoutSeconds = preferences[openAiTimeoutSecondsKey] ?: OPENAI_DEFAULT_TIMEOUT_SECONDS,
            )
        }

    suspend fun saveProviderSettings(settings: ProviderSettingsSnapshot) {
        context.settingsDataStore.edit { preferences ->
            preferences[providerTypeKey] = settings.providerType.storageValue
            preferences[openAiBaseUrlKey] = settings.openAiBaseUrl.trim()
            preferences[openAiModelIdKey] = settings.openAiModelId.trim()
            preferences[openAiTimeoutSecondsKey] = settings.openAiTimeoutSeconds
        }
    }

    suspend fun setProviderType(providerType: ProviderType) {
        context.settingsDataStore.edit { preferences ->
            preferences[providerTypeKey] = providerType.storageValue
        }
    }
}
