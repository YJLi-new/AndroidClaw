package ai.androidclaw.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

private val Context.skillConfigDataStore by preferencesDataStore(name = "androidclaw_skill_config")

internal const val SKILL_CONFIG_SKILL_KEY_MAX_CHARS = 160
internal const val SKILL_CONFIG_PATH_MAX_CHARS = 512
internal const val SKILL_CONFIG_VALUE_MAX_CHARS = 4_000

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
    ): String? {
        val key = storageKey(skillKey, configPath) ?: return null
        return readPreferences()[key].toBoundedSkillConfigValue()
    }

    override suspend fun readConfigs(skillKey: String): Map<String, String> {
        val prefix = storagePrefix(skillKey) ?: return emptyMap()
        return readPreferences()
            .asMap()
            .entries
            .mapNotNull { (key, value) ->
                val preferenceKey = key.name
                if (!preferenceKey.startsWith(prefix)) {
                    return@mapNotNull null
                }
                val configPath =
                    decodeSegment(preferenceKey.removePrefix(prefix))
                        ?.toBoundedSkillConfigIdentifier(SKILL_CONFIG_PATH_MAX_CHARS)
                        ?: return@mapNotNull null
                val storedValue = (value as? String).toBoundedSkillConfigValue() ?: return@mapNotNull null
                configPath to storedValue
            }.toMap()
    }

    override suspend fun writeConfig(
        skillKey: String,
        configPath: String,
        value: String?,
    ) {
        val key = storageKey(skillKey, configPath) ?: return
        context.skillConfigDataStore.edit { preferences ->
            val normalized = value.toBoundedSkillConfigValue()
            if (normalized == null) {
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
    ): Preferences.Key<String>? {
        val prefix = storagePrefix(skillKey) ?: return null
        val normalizedConfigPath =
            configPath.toBoundedSkillConfigIdentifier(SKILL_CONFIG_PATH_MAX_CHARS) ?: return null
        return stringPreferencesKey(prefix + encodeSegment(normalizedConfigPath))
    }

    private fun storagePrefix(skillKey: String): String? =
        skillKey
            .toBoundedSkillConfigIdentifier(SKILL_CONFIG_SKILL_KEY_MAX_CHARS)
            ?.let { normalizedSkillKey -> "cfg:${encodeSegment(normalizedSkillKey)}:" }

    private fun encodeSegment(value: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeSegment(value: String): String? =
        runCatching {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        }.getOrNull()
}

internal fun String?.toBoundedSkillConfigValue(): String? =
    this
        ?.trim()
        ?.take(SKILL_CONFIG_VALUE_MAX_CHARS)
        ?.takeIf(String::isNotBlank)

internal fun String.toBoundedSkillConfigIdentifier(maxChars: Int): String? {
    val trimmed = trim()
    if (trimmed.isBlank()) {
        return null
    }
    if (trimmed.length <= maxChars) {
        return trimmed
    }
    val hashSuffix = trimmed.sha256Hex().take(SKILL_CONFIG_HASH_CHARS)
    val prefixLength = (maxChars - SKILL_CONFIG_HASH_CHARS - 1).coerceAtLeast(1)
    return "${trimmed.take(prefixLength)}#$hashSuffix"
}

private const val SKILL_CONFIG_HASH_CHARS = 12

private fun String.sha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff)
                .toString(radix = 16)
                .padStart(length = 2, padChar = '0')
        }
