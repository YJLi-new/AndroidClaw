package ai.androidclaw.testutil

import ai.androidclaw.data.SkillConfigStore
import ai.androidclaw.data.SkillSecretStore

class InMemorySkillConfigStore(
    initialValues: Map<Pair<String, String>, String> = emptyMap(),
) : SkillConfigStore {
    private val values = initialValues.toMutableMap()

    override suspend fun readConfig(
        skillKey: String,
        configPath: String,
    ): String? = values[skillKey to configPath]

    override suspend fun readConfigs(skillKey: String): Map<String, String> =
        values
            .filterKeys { (storedSkillKey, _) -> storedSkillKey == skillKey }
            .mapKeys { (key, _) -> key.second }

    override suspend fun writeConfig(
        skillKey: String,
        configPath: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            values.remove(skillKey to configPath)
        } else {
            values[skillKey to configPath] = value.trim()
        }
    }
}

class InMemorySkillSecretStore(
    initialValues: Map<Pair<String, String>, String> = emptyMap(),
    initialRecoveryNotices: Set<Pair<String, String>> = emptySet(),
) : SkillSecretStore {
    private val values = initialValues.toMutableMap()
    private val recoveryNotices = initialRecoveryNotices.toMutableSet()

    override suspend fun readSecret(
        skillKey: String,
        envName: String,
    ): String? = values[skillKey to envName]

    override suspend fun writeSecret(
        skillKey: String,
        envName: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            values.remove(skillKey to envName)
        } else {
            values[skillKey to envName] = value.trim()
        }
    }

    override suspend fun consumeRecoveryNotice(
        skillKey: String,
        envName: String,
    ): Boolean = recoveryNotices.remove(skillKey to envName)

    fun markRecoveryNotice(
        skillKey: String,
        envName: String,
    ) {
        recoveryNotices += skillKey to envName
    }
}
