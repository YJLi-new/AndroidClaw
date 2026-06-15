package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.SkillRecordDao
import ai.androidclaw.data.db.entity.SkillRecordEntity
import ai.androidclaw.data.model.SkillRecord
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillSourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant

internal const val SKILL_KEY_MAX_CHARS = 160
internal const val SKILL_BASE_DIR_MAX_CHARS = 1_000
internal const val SKILL_DISPLAY_NAME_MAX_CHARS = 160
internal const val SKILL_DESCRIPTION_MAX_CHARS = 1_000
internal const val SKILL_INSTRUCTIONS_MAX_CHARS = 20_000
internal const val SKILL_PARSE_ERROR_MAX_CHARS = 1_000
internal const val SKILL_ELIGIBILITY_REASON_MAX_CHARS = 1_000
internal const val SKILL_ELIGIBILITY_REASONS_MAX_COUNT = 20

class SkillRepository(
    private val dao: SkillRecordDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun upsertSkill(record: SkillRecord) {
        dao.upsert(record.toEntity(json))
    }

    suspend fun upsertAll(records: List<SkillRecord>) {
        dao.upsertAll(records.map { it.toEntity(json) })
    }

    fun observeSkills(): Flow<List<SkillRecord>> =
        dao.getAll().map { records ->
            records.map { it.toDomain(json) }
        }

    suspend fun getAllSkills(): List<SkillRecord> = dao.getAllOnce().map { it.toDomain(json) }

    suspend fun getEnabledSkills(): List<SkillRecord> = dao.getEnabled().map { it.toDomain(json) }

    suspend fun getSkill(id: String): SkillRecord? = dao.getById(id)?.toDomain(json)

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        val existing = dao.getById(id) ?: return
        dao.upsert(
            existing.copy(
                enabled = enabled,
                updatedAt = Instant.now().toEpochMilli(),
            ),
        )
    }

    suspend fun deleteSkill(id: String) {
        dao.delete(id)
    }
}

private fun SkillRecord.toEntity(json: Json): SkillRecordEntity {
    val boundedFrontmatter = frontmatter?.toBoundedSkillFrontmatter()
    return SkillRecordEntity(
        id = id,
        skillKey = skillKey.toBoundedSkillText(SKILL_KEY_MAX_CHARS),
        sourceType = sourceType.toStorage(),
        workspaceSessionId = workspaceSessionId,
        baseDir = baseDir.toBoundedSkillText(SKILL_BASE_DIR_MAX_CHARS),
        enabled = enabled,
        displayName = displayName.toBoundedSkillText(SKILL_DISPLAY_NAME_MAX_CHARS),
        description = description.toBoundedSkillText(SKILL_DESCRIPTION_MAX_CHARS),
        frontmatterJson = boundedFrontmatter?.let { json.encodeToString(SkillFrontmatter.serializer(), it) },
        instructionsMd = instructionsMd.toBoundedSkillText(SKILL_INSTRUCTIONS_MAX_CHARS),
        eligibilityStatus = eligibilityStatus.toStorage(),
        eligibilityReasons =
            json.encodeToString(
                ListSerializer(String.serializer()),
                eligibilityReasons.toBoundedSkillReasons(),
            ),
        parseError = parseError?.toBoundedSkillText(SKILL_PARSE_ERROR_MAX_CHARS),
        importedAt = importedAt?.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
    )
}

private fun SkillRecordEntity.toDomain(json: Json): SkillRecord =
    SkillRecord(
        id = id,
        skillKey = skillKey.toBoundedSkillText(SKILL_KEY_MAX_CHARS),
        sourceType = sourceType.toSkillSourceType(),
        workspaceSessionId = workspaceSessionId,
        baseDir = baseDir.toBoundedSkillText(SKILL_BASE_DIR_MAX_CHARS),
        enabled = enabled,
        displayName = displayName.toBoundedSkillText(SKILL_DISPLAY_NAME_MAX_CHARS),
        description = description.toBoundedSkillText(SKILL_DESCRIPTION_MAX_CHARS),
        frontmatter = decodeFrontmatter(json, frontmatterJson)?.toBoundedSkillFrontmatter(),
        instructionsMd = instructionsMd.toBoundedSkillText(SKILL_INSTRUCTIONS_MAX_CHARS),
        eligibilityStatus = eligibilityStatus.toSkillEligibilityStatus(),
        eligibilityReasons = decodeEligibilityReasons(json, eligibilityReasons).toBoundedSkillReasons(),
        parseError = parseError?.toBoundedSkillText(SKILL_PARSE_ERROR_MAX_CHARS),
        importedAt = importedAt?.let(Instant::ofEpochMilli),
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )

private fun decodeFrontmatter(
    json: Json,
    rawValue: String?,
): SkillFrontmatter? {
    rawValue ?: return null
    return try {
        json.decodeFromString(SkillFrontmatter.serializer(), rawValue)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun decodeEligibilityReasons(
    json: Json,
    rawValue: String,
): List<String> =
    try {
        json.decodeFromString(ListSerializer(String.serializer()), rawValue)
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

private fun SkillFrontmatter.toBoundedSkillFrontmatter(): SkillFrontmatter =
    copy(
        name = name.toBoundedSkillText(SKILL_DISPLAY_NAME_MAX_CHARS),
        description = description.toBoundedSkillText(SKILL_DESCRIPTION_MAX_CHARS),
        homepage = homepage?.toBoundedSkillText(SKILL_BASE_DIR_MAX_CHARS),
        commandTool = commandTool?.toBoundedSkillText(SKILL_KEY_MAX_CHARS),
        commandArgMode = commandArgMode.toBoundedSkillText(SKILL_KEY_MAX_CHARS),
    )

private fun List<String>.toBoundedSkillReasons(): List<String> =
    asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { it.toBoundedSkillText(SKILL_ELIGIBILITY_REASON_MAX_CHARS) }
        .distinct()
        .take(SKILL_ELIGIBILITY_REASONS_MAX_COUNT)
        .toList()

private fun String.toBoundedSkillText(maxChars: Int): String = take(maxChars)

private fun SkillSourceType.toStorage(): String =
    when (this) {
        SkillSourceType.Bundled -> "bundled"
        SkillSourceType.Local -> "local"
        SkillSourceType.Workspace -> "workspace"
    }

private fun String.toSkillSourceType(): SkillSourceType =
    when (this) {
        "bundled" -> SkillSourceType.Bundled
        "local" -> SkillSourceType.Local
        "workspace" -> SkillSourceType.Workspace
        else -> SkillSourceType.Local
    }

private fun SkillEligibilityStatus.toStorage(): String =
    when (this) {
        SkillEligibilityStatus.Eligible -> "Eligible"
        SkillEligibilityStatus.Invalid -> "Invalid"
        SkillEligibilityStatus.MissingTool -> "MissingTool"
        SkillEligibilityStatus.BridgeOnly -> "BridgeOnly"
    }

private fun String.toSkillEligibilityStatus(): SkillEligibilityStatus =
    when (this) {
        "Eligible" -> SkillEligibilityStatus.Eligible
        "Invalid" -> SkillEligibilityStatus.Invalid
        "MissingTool" -> SkillEligibilityStatus.MissingTool
        "BridgeOnly" -> SkillEligibilityStatus.BridgeOnly
        else -> SkillEligibilityStatus.Invalid
    }
