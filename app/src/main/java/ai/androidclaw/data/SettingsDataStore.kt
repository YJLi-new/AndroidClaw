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

internal const val PROVIDER_BASE_URL_MAX_CHARS = 2_048
internal const val PROVIDER_MODEL_ID_MAX_CHARS = 256

class SettingsDataStore(
    private val context: Context,
) {
    private val providerTypeKey = stringPreferencesKey("provider_type")
    private val themePreferenceKey = stringPreferencesKey("theme_preference")
    private val memoryEnabledKey = booleanPreferencesKey("memory_enabled")
    private val memoryInstallUserIdKey = stringPreferencesKey("memory_install_user_id")
    private val webSearchEnabledKey = booleanPreferencesKey("web_search_enabled")
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
                                acceptsImages = preferences[acceptsImagesKey(providerType)] ?: false,
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

    /** Settings → Web search: Automatic (true, default) or Off. */
    val webSearchEnabled: Flow<Boolean> =
        context.settingsDataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }.map { preferences -> preferences[webSearchEnabledKey] ?: true }

    suspend fun setWebSearchEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[webSearchEnabledKey] = enabled
        }
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
                val baseUrl = normalizeProviderBaseUrl(providerType, providerSettings.baseUrl)
                val modelId = normalizeProviderModelId(providerType, providerSettings.modelId)
                preferences[baseUrlKey(providerType)] = baseUrl
                preferences[modelIdKey(providerType)] = modelId
                preferences[timeoutSecondsKey(providerType)] = timeoutSeconds
                preferences[acceptsImagesKey(providerType)] = providerSettings.acceptsImages
                if (providerType == ProviderType.OpenAiCompatible) {
                    preferences[legacyOpenAiBaseUrlKey] = baseUrl
                    preferences[legacyOpenAiModelIdKey] = modelId
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
        }.let { rawValue ->
            normalizeProviderBaseUrl(providerType, rawValue)
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
        }.let { rawValue ->
            normalizeProviderModelId(providerType, rawValue)
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

    private fun acceptsImagesKey(providerType: ProviderType) = booleanPreferencesKey("provider_${providerType.storageValue}_accepts_images")

    // PLANv13 gen: Settings → Image and video.
    private val imageModelIdKey = stringPreferencesKey("media_image_model_id")
    private val videoModelIdKey = stringPreferencesKey("media_video_model_id")

    /** OpenAI Images model for `image_generate`; the default comes from the 2026-11-01 model listing. */
    val imageModelId: Flow<String> =
        context.settingsDataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map { preferences -> normalizeMediaModelId(preferences[imageModelIdKey], IMAGE_GENERATION_DEFAULT_MODEL_ID) }

    /** OpenAI Videos model for `video_generate`. */
    val videoModelId: Flow<String> =
        context.settingsDataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map { preferences -> normalizeMediaModelId(preferences[videoModelIdKey], VIDEO_GENERATION_DEFAULT_MODEL_ID) }

    suspend fun setImageModelId(modelId: String) {
        context.settingsDataStore.edit { it[imageModelIdKey] = normalizeMediaModelId(modelId, IMAGE_GENERATION_DEFAULT_MODEL_ID) }
    }

    suspend fun setVideoModelId(modelId: String) {
        context.settingsDataStore.edit { it[videoModelIdKey] = normalizeMediaModelId(modelId, VIDEO_GENERATION_DEFAULT_MODEL_ID) }
    }

    // PLANv13 voice
    private val voiceSendRightAwayKey = booleanPreferencesKey("voice_send_right_away")
    private val voiceRepliesKey = booleanPreferencesKey("voice_replies")

    /** Settings → Voice and the chat menu's "Voice replies"; both default off. */
    val voiceSettings: Flow<VoiceSettings> =
        context.settingsDataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }.map { preferences ->
                VoiceSettings(
                    sendRightAway = preferences[voiceSendRightAwayKey] ?: false,
                    voiceReplies = preferences[voiceRepliesKey] ?: false,
                )
            }

    suspend fun setVoiceSendRightAway(enabled: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[voiceSendRightAwayKey] = enabled }
    }

    suspend fun setVoiceReplies(enabled: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[voiceRepliesKey] = enabled }
    }
}

/** Newest `gpt-image-*` listed by `/v1/models` on 2026-11-01 (tie-break: newest `created`). */
const val IMAGE_GENERATION_DEFAULT_MODEL_ID: String = "gpt-image-2.5-sunburst"
const val VIDEO_GENERATION_DEFAULT_MODEL_ID: String = "sora-2"

internal fun normalizeMediaModelId(
    rawValue: String?,
    fallback: String,
): String =
    rawValue
        ?.trim()
        ?.take(PROVIDER_MODEL_ID_MAX_CHARS)
        ?.takeIf(String::isNotBlank)
        ?: fallback

data class VoiceSettings(
    val sendRightAway: Boolean = false,
    val voiceReplies: Boolean = false,
)

private fun normalizeProviderBaseUrl(
    providerType: ProviderType,
    rawValue: String,
): String =
    rawValue
        .trim()
        .take(PROVIDER_BASE_URL_MAX_CHARS)
        .takeIf(String::isNotBlank)
        ?: providerType.defaultBaseUrl

private fun normalizeProviderModelId(
    providerType: ProviderType,
    rawValue: String,
): String {
    val trimmedValue = rawValue.trim()
    val migratedValue =
        if (providerType == ProviderType.OpenAiCodex && trimmedValue == OPENAI_CODEX_LEGACY_DEFAULT_MODEL_ID) {
            OPENAI_CODEX_DEFAULT_MODEL_ID
        } else {
            trimmedValue
        }
    return migratedValue
        .take(PROVIDER_MODEL_ID_MAX_CHARS)
        .takeIf(String::isNotBlank)
        ?: providerType.defaultModelId
}
