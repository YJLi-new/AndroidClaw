package ai.androidclaw.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.nio.charset.StandardCharsets
import java.util.Base64

private val Context.skillConfigDataStore by preferencesDataStore(name = "androidclaw_skill_config")

interface SkillConfigStore {
    suspend fun readConfig(
        skillKey: String,
        configPath: String,
    ): String?

    suspend fun readConfigs(skillKey: String): Map<String, String>

    suspend fun writeConfig(
        skillKey: String,
        configPath: String,
        value: String?,
    )
}

class AndroidSkillConfigStore(
    private val context: Context,
) : SkillConfigStore {
    override suspend fun readConfig(
        skillKey: String,
        configPath: String,
    ): String? = readPreferences()[storageKey(skillKey, configPath)]?.trim()?.takeIf(String::isNotBlank)

    override suspend fun readConfigs(skillKey: String): Map<String, String> {
        val prefix = storagePrefix(skillKey)
        return readPreferences()
            .asMap()
            .entries
            .mapNotNull { (key, value) ->
                val preferenceKey = key.name
                if (!preferenceKey.startsWith(prefix)) {
                    return@mapNotNull null
                }
                val configPath = decodeSegment(preferenceKey.removePrefix(prefix))
                val storedValue = (value as? String)?.trim().orEmpty()
                if (storedValue.isBlank()) {
                    null
                } else {
                    configPath to storedValue
                }
            }.toMap()
    }

    override suspend fun writeConfig(
        skillKey: String,
        configPath: String,
        value: String?,
    ) {
        val key = storageKey(skillKey, configPath)
        context.skillConfigDataStore.edit { preferences ->
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) {
                preferences.remove(key)
            } else {
                preferences[key] = normalized
            }
        }
    }

    private suspend fun readPreferences(): Preferences = context.skillConfigDataStore.data.first()

    private fun storageKey(
        skillKey: String,
        configPath: String,
    ) = stringPreferencesKey(storagePrefix(skillKey) + encodeSegment(configPath))

    private fun storagePrefix(skillKey: String): String = "cfg:${encodeSegment(skillKey)}:"

    private fun encodeSegment(value: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.trim().toByteArray(StandardCharsets.UTF_8))

    private fun decodeSegment(value: String): String = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
