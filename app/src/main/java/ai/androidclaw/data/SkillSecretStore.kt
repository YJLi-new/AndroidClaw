package ai.androidclaw.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal const val SKILL_SECRET_SKILL_KEY_MAX_CHARS = 160
internal const val SKILL_SECRET_ENV_NAME_MAX_CHARS = 160
internal const val SKILL_SECRET_VALUE_MAX_CHARS = 20_000

interface SkillSecretStore {
    suspend fun readSecret(
        skillKey: String,
        envName: String,
    ): String?

    suspend fun writeSecret(
        skillKey: String,
        envName: String,
        value: String?,
    )

    suspend fun consumeRecoveryNotice(
        skillKey: String,
        envName: String,
    ): Boolean
}

class AndroidSkillSecretStore(
    context: Context,
) : SkillSecretStore {
    private val encryptedStore =
        EncryptedStringStore(
            context = context,
            preferencesName = PREFERENCES_NAME,
            keyAlias = KEY_ALIAS,
        )

    override suspend fun readSecret(
        skillKey: String,
        envName: String,
    ): String? =
        withContext(Dispatchers.IO) {
            skillSecretStorageKeyCandidates(skillKey, envName)
                .firstNotNullOfOrNull { storageKey ->
                    encryptedStore.read(storageKey).toBoundedSkillSecretValue()
                }
        }

    override suspend fun writeSecret(
        skillKey: String,
        envName: String,
        value: String?,
    ) = withContext(Dispatchers.IO) {
        val storageKeys = skillSecretStorageKeyCandidates(skillKey, envName)
        if (storageKeys.isEmpty()) {
            return@withContext
        }
        val normalizedValue = value.toBoundedSkillSecretValue()
        encryptedStore.write(storageKeys.first(), normalizedValue)
        storageKeys.drop(1).forEach { legacyStorageKey ->
            encryptedStore.write(legacyStorageKey, null)
        }
    }

    override suspend fun consumeRecoveryNotice(
        skillKey: String,
        envName: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            skillSecretStorageKeyCandidates(skillKey, envName)
                .fold(false) { recovered, storageKey ->
                    encryptedStore.consumeRecoveryNotice(storageKey) || recovered
                }
        }

    private companion object {
        const val PREFERENCES_NAME = "androidclaw_skill_secrets"
        const val KEY_ALIAS = "androidclaw_skill_secret_key"
    }
}

internal fun String?.toBoundedSkillSecretValue(): String? =
    this
        ?.trim()
        ?.take(SKILL_SECRET_VALUE_MAX_CHARS)
        ?.takeIf(String::isNotBlank)

internal fun String.toBoundedSkillSecretIdentifier(maxChars: Int): String? {
    val trimmed = trim()
    if (trimmed.isBlank()) {
        return null
    }
    if (trimmed.length <= maxChars) {
        return trimmed
    }
    val hashSuffix = trimmed.sha256Hex().take(SKILL_SECRET_HASH_CHARS)
    val prefixLength = (maxChars - SKILL_SECRET_HASH_CHARS - 1).coerceAtLeast(1)
    return "${trimmed.take(prefixLength)}#$hashSuffix"
}

internal fun skillSecretStorageKeyCandidates(
    skillKey: String,
    envName: String,
): List<String> {
    val normalizedSkillKey = skillKey.toBoundedSkillSecretIdentifier(SKILL_SECRET_SKILL_KEY_MAX_CHARS)
    val normalizedEnvName = envName.toBoundedSkillSecretIdentifier(SKILL_SECRET_ENV_NAME_MAX_CHARS)
    if (normalizedSkillKey == null || normalizedEnvName == null) {
        return emptyList()
    }

    val primaryKey =
        "skill_secret_v2_${normalizedSkillKey.toSkillSecretStorageSegment()}:${normalizedEnvName.toSkillSecretStorageSegment()}"
    val legacyKey =
        if (
            skillKey.trim().isNotBlank() &&
            envName.trim().isNotBlank() &&
            skillKey.trim().length <= SKILL_SECRET_SKILL_KEY_MAX_CHARS &&
            envName.trim().length <= SKILL_SECRET_ENV_NAME_MAX_CHARS
        ) {
            "skill_secret_${skillKey.trim()}:${envName.trim()}"
        } else {
            null
        }
    return listOfNotNull(primaryKey, legacyKey?.takeIf { it != primaryKey })
}

private const val SKILL_SECRET_HASH_CHARS = 12

private fun String.toSkillSecretStorageSegment(): String =
    Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString(toByteArray(StandardCharsets.UTF_8))

private fun String.sha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff)
                .toString(radix = 16)
                .padStart(length = 2, padChar = '0')
        }
