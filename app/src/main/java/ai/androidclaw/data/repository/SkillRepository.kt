package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.SkillRecordDao
import ai.androidclaw.data.db.entity.SkillRecordEntity
import ai.androidclaw.data.model.SkillRecord
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillSourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant

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

private fun SkillRecord.toEntity(json: Json): SkillRecordEntity =
    SkillRecordEntity(
        id = id,
        skillKey = skillKey,
        sourceType = sourceType.toStorage(),
        workspaceSessionId = workspaceSessionId,
        baseDir = baseDir,
        enabled = enabled,
        displayName = displayName,
        description = description,
        frontmatterJson = frontmatter?.let { json.encodeToString(SkillFrontmatter.serializer(), it) },
        instructionsMd = instructionsMd,
        eligibilityStatus = eligibilityStatus.toStorage(),
        eligibilityReasons = json.encodeToString(ListSerializer(String.serializer()), eligibilityReasons),
        parseError = parseError,
        importedAt = importedAt?.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
    )

private fun SkillRecordEntity.toDomain(json: Json): SkillRecord =
    SkillRecord(
        id = id,
        skillKey = skillKey,
        sourceType = sourceType.toSkillSourceType(),
        workspaceSessionId = workspaceSessionId,
        baseDir = baseDir,
        enabled = enabled,
        displayName = displayName,
        description = description,
        frontmatter = frontmatterJson?.let { json.decodeFromString(SkillFrontmatter.serializer(), it) },
        instructionsMd = instructionsMd,
        eligibilityStatus = eligibilityStatus.toSkillEligibilityStatus(),
        eligibilityReasons = json.decodeFromString(ListSerializer(String.serializer()), eligibilityReasons),
        parseError = parseError,
        importedAt = importedAt?.let(Instant::ofEpochMilli),
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )

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
