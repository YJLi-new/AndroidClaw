package ai.androidclaw.data

import android.content.Context

interface SkillSecretStore {
    suspend fun readSecret(skillKey: String, envName: String): String?

    suspend fun writeSecret(skillKey: String, envName: String, value: String?)
}

class AndroidSkillSecretStore(
    context: Context,
) : SkillSecretStore {
    private val encryptedStore = EncryptedStringStore(
        context = context,
        preferencesName = PREFERENCES_NAME,
        keyAlias = KEY_ALIAS,
    )

    override suspend fun readSecret(skillKey: String, envName: String): String? {
        return encryptedStore.read(storageKey(skillKey, envName))
    }

    override suspend fun writeSecret(skillKey: String, envName: String, value: String?) {
        encryptedStore.write(storageKey(skillKey, envName), value)
    }

    private fun storageKey(skillKey: String, envName: String): String {
        return "skill_secret_${skillKey.trim()}:${envName.trim()}"
    }

    private companion object {
        const val PREFERENCES_NAME = "androidclaw_skill_secrets"
        const val KEY_ALIAS = "androidclaw_skill_secret_key"
    }
}
