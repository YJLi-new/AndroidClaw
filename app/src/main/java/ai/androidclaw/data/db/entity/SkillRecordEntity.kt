package ai.androidclaw.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "skill_records",
    indices = [
        Index(value = ["sourceType"]),
        Index(value = ["enabled"]),
    ],
)
data class SkillRecordEntity(
    @PrimaryKey val id: String,
    val skillKey: String,
    val sourceType: String,
    val workspaceSessionId: String?,
    val baseDir: String,
    val enabled: Boolean,
    val displayName: String,
    val description: String,
    val frontmatterJson: String?,
    val instructionsMd: String,
    val eligibilityStatus: String,
    val eligibilityReasons: String,
    val parseError: String?,
    val importedAt: Long?,
    val updatedAt: Long,
)
