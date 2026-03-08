package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.SkillRecordDao
import ai.androidclaw.data.db.entity.SkillRecordEntity
import ai.androidclaw.data.model.SkillRecord
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillSourceType
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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

    fun observeSkills(): Flow<List<SkillRecord>> = dao.getAll().map { records ->
        records.map { it.toDomain(json) }
    }

    suspend fun getEnabledSkills(): List<SkillRecord> {
        return dao.getEnabled().map { it.toDomain(json) }
    }

    suspend fun getSkill(id: String): SkillRecord? = dao.getById(id)?.toDomain(json)

    suspend fun setEnabled(id: String, enabled: Boolean) {
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
    return SkillRecordEntity(
        id = id,
        sourceType = sourceType.toStorage(),
        enabled = enabled,
        displayName = displayName,
        description = description,
        frontmatterJson = frontmatter?.let { json.encodeToString(SkillFrontmatter.serializer(), it) },
        eligibilityStatus = eligibilityStatus.toStorage(),
        eligibilityReasons = json.encodeToString(ListSerializer(String.serializer()), eligibilityReasons),
        importedAt = importedAt?.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
    )
}

private fun SkillRecordEntity.toDomain(json: Json): SkillRecord {
    return SkillRecord(
        id = id,
        sourceType = sourceType.toSkillSourceType(),
        enabled = enabled,
        displayName = displayName,
        description = description,
        frontmatter = frontmatterJson?.let { json.decodeFromString(SkillFrontmatter.serializer(), it) },
        eligibilityStatus = eligibilityStatus.toSkillEligibilityStatus(),
        eligibilityReasons = json.decodeFromString(ListSerializer(String.serializer()), eligibilityReasons),
        importedAt = importedAt?.let(Instant::ofEpochMilli),
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )
}

private fun SkillSourceType.toStorage(): String {
    return when (this) {
        SkillSourceType.Bundled -> "bundled"
        SkillSourceType.Local -> "local"
        SkillSourceType.Workspace -> "workspace"
    }
}

private fun String.toSkillSourceType(): SkillSourceType {
    return when (this) {
        "bundled" -> SkillSourceType.Bundled
        "local" -> SkillSourceType.Local
        "workspace" -> SkillSourceType.Workspace
        else -> error("Unsupported skill source type: $this")
    }
}

private fun SkillEligibilityStatus.toStorage(): String {
    return when (this) {
        SkillEligibilityStatus.Eligible -> "Eligible"
        SkillEligibilityStatus.Invalid -> "Invalid"
        SkillEligibilityStatus.MissingTool -> "MissingTool"
        SkillEligibilityStatus.BridgeOnly -> "BridgeOnly"
    }
}

private fun String.toSkillEligibilityStatus(): SkillEligibilityStatus {
    return when (this) {
        "Eligible" -> SkillEligibilityStatus.Eligible
        "Invalid" -> SkillEligibilityStatus.Invalid
        "MissingTool" -> SkillEligibilityStatus.MissingTool
        "BridgeOnly" -> SkillEligibilityStatus.BridgeOnly
        else -> error("Unsupported skill eligibility status: $this")
    }
}
