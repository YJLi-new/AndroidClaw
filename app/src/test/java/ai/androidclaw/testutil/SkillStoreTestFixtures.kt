package ai.androidclaw.testutil

import ai.androidclaw.data.SKILL_CONFIG_PATH_MAX_CHARS
import ai.androidclaw.data.SKILL_CONFIG_SKILL_KEY_MAX_CHARS
import ai.androidclaw.data.SKILL_SECRET_ENV_NAME_MAX_CHARS
import ai.androidclaw.data.SKILL_SECRET_SKILL_KEY_MAX_CHARS
import ai.androidclaw.data.SkillConfigStore
import ai.androidclaw.data.SkillSecretStore
import ai.androidclaw.data.toBoundedSkillConfigIdentifier
import ai.androidclaw.data.toBoundedSkillConfigValue
import ai.androidclaw.data.toBoundedSkillSecretIdentifier
import ai.androidclaw.data.toBoundedSkillSecretValue

class InMemorySkillConfigStore(
    initialValues: Map<Pair<String, String>, String> = emptyMap(),
) : SkillConfigStore {
    private val values =
        initialValues
            .mapNotNull { (key, value) ->
                val skillKey = key.first.toBoundedSkillConfigIdentifier(SKILL_CONFIG_SKILL_KEY_MAX_CHARS)
                val configPath = key.second.toBoundedSkillConfigIdentifier(SKILL_CONFIG_PATH_MAX_CHARS)
                val normalizedValue = value.toBoundedSkillConfigValue()
                if (skillKey == null || configPath == null || normalizedValue == null) {
                    null
                } else {
                    (skillKey to configPath) to normalizedValue
                }
            }.toMap()
            .toMutableMap()

    override suspend fun readConfig(
        skillKey: String,
        configPath: String,
    ): String? {
        val normalizedSkillKey = skillKey.toBoundedSkillConfigIdentifier(SKILL_CONFIG_SKILL_KEY_MAX_CHARS) ?: return null
        val normalizedConfigPath = configPath.toBoundedSkillConfigIdentifier(SKILL_CONFIG_PATH_MAX_CHARS) ?: return null
        return values[normalizedSkillKey to normalizedConfigPath]
    }

    override suspend fun readConfigs(skillKey: String): Map<String, String> {
        val normalizedSkillKey = skillKey.toBoundedSkillConfigIdentifier(SKILL_CONFIG_SKILL_KEY_MAX_CHARS) ?: return emptyMap()
        return values
            .filterKeys { (storedSkillKey, _) -> storedSkillKey == normalizedSkillKey }
            .mapKeys { (key, _) -> key.second }
    }

    override suspend fun writeConfig(
        skillKey: String,
        configPath: String,
        value: String?,
    ) {
        val normalizedSkillKey = skillKey.toBoundedSkillConfigIdentifier(SKILL_CONFIG_SKILL_KEY_MAX_CHARS) ?: return
        val normalizedConfigPath = configPath.toBoundedSkillConfigIdentifier(SKILL_CONFIG_PATH_MAX_CHARS) ?: return
        val normalizedValue = value.toBoundedSkillConfigValue()
        if (normalizedValue == null) {
            values.remove(normalizedSkillKey to normalizedConfigPath)
        } else {
            values[normalizedSkillKey to normalizedConfigPath] = normalizedValue
        }
    }
}

class InMemorySkillSecretStore(
    initialValues: Map<Pair<String, String>, String> = emptyMap(),
    initialRecoveryNotices: Set<Pair<String, String>> = emptySet(),
) : SkillSecretStore {
    private val values =
        initialValues
            .mapNotNull { (key, value) ->
                val normalizedKey = key.toNormalizedSkillSecretKey() ?: return@mapNotNull null
                val normalizedValue = value.toBoundedSkillSecretValue() ?: return@mapNotNull null
                normalizedKey to normalizedValue
            }.toMap()
            .toMutableMap()
    private val recoveryNotices =
        initialRecoveryNotices
            .mapNotNull { it.toNormalizedSkillSecretKey() }
            .toMutableSet()

    override suspend fun readSecret(
        skillKey: String,
        envName: String,
    ): String? = values[(skillKey to envName).toNormalizedSkillSecretKey()]

    override suspend fun writeSecret(
        skillKey: String,
        envName: String,
        value: String?,
    ) {
        val normalizedKey = (skillKey to envName).toNormalizedSkillSecretKey() ?: return
        val normalizedValue = value.toBoundedSkillSecretValue()
        if (normalizedValue == null) {
            values.remove(normalizedKey)
        } else {
            values[normalizedKey] = normalizedValue
        }
    }

    override suspend fun consumeRecoveryNotice(
        skillKey: String,
        envName: String,
    ): Boolean = recoveryNotices.remove((skillKey to envName).toNormalizedSkillSecretKey())

    fun markRecoveryNotice(
        skillKey: String,
        envName: String,
    ) {
        (skillKey to envName).toNormalizedSkillSecretKey()?.let(recoveryNotices::add)
    }
}

private fun Pair<String, String>.toNormalizedSkillSecretKey(): Pair<String, String>? {
    val skillKey = first.toBoundedSkillSecretIdentifier(SKILL_SECRET_SKILL_KEY_MAX_CHARS) ?: return null
    val envName = second.toBoundedSkillSecretIdentifier(SKILL_SECRET_ENV_NAME_MAX_CHARS) ?: return null
    return skillKey to envName
}
