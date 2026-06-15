package ai.androidclaw.runtime.skills

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val SKILL_STORAGE_SEGMENT_MAX_CHARS = 120

class SkillStorage(
    private val filesDir: File,
    private val cacheDir: File,
) {
    val localSkillsDir: File
        get() = File(filesDir, "skills/local")

    fun workspaceSkillsDir(sessionId: String): File =
        File(
            File(File(filesDir, "workspaces"), sessionId.toSkillStorageSegment(fallbackPrefix = "session")),
            "skills",
        )

    fun importScratchDir(label: String): File =
        File(
            cacheDir,
            "skill-import-${label.toSkillStorageSegment(fallbackPrefix = "import")}",
        )
}

internal fun String.toSkillStorageSegment(fallbackPrefix: String): String {
    val trimmed = trim()
    if (trimmed.matches(safeSkillStorageSegmentPattern)) {
        return trimmed
    }

    val maxSlugChars = SKILL_STORAGE_SEGMENT_MAX_CHARS - SKILL_STORAGE_HASH_CHARS - 1
    val fallbackSlug =
        fallbackPrefix
            .asSequence()
            .map { char -> char.toSafeSkillStorageSegmentChar() }
            .joinToString(separator = "")
            .trim('.', '-', '_')
            .take(maxSlugChars)
            .ifBlank { "item" }
    val slug =
        trimmed
            .asSequence()
            .map { char -> char.toSafeSkillStorageSegmentChar() }
            .joinToString(separator = "")
            .trim('.', '-', '_')
            .take(maxSlugChars)
            .trim('.', '-', '_')
            .ifBlank { fallbackSlug }
    return "$slug-${trimmed.sha256Hex().take(SKILL_STORAGE_HASH_CHARS)}"
}

private val safeSkillStorageSegmentPattern =
    Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,${SKILL_STORAGE_SEGMENT_MAX_CHARS - 1}}")

private const val SKILL_STORAGE_HASH_CHARS = 12

private fun Char.toSafeSkillStorageSegmentChar(): Char =
    if (isSafeSkillStorageSegmentChar()) {
        this
    } else {
        '-'
    }

private fun Char.isSafeSkillStorageSegmentChar(): Boolean =
    this in 'a'..'z' ||
        this in 'A'..'Z' ||
        this in '0'..'9' ||
        this == '.' ||
        this == '_' ||
        this == '-'

private fun String.sha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff)
                .toString(radix = 16)
                .padStart(length = 2, padChar = '0')
        }
