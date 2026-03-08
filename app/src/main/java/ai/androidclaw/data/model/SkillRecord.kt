package ai.androidclaw.data.model

import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillSourceType
import java.time.Instant

data class SkillRecord(
    val id: String,
    val sourceType: SkillSourceType,
    val enabled: Boolean,
    val displayName: String,
    val description: String,
    val frontmatter: SkillFrontmatter?,
    val eligibilityStatus: SkillEligibilityStatus,
    val eligibilityReasons: List<String>,
    val importedAt: Instant?,
    val updatedAt: Instant,
)
