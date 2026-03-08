package ai.androidclaw.runtime.skills

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class BundledSkillLoader(
    private val assetManager: AssetManager,
    private val rootPath: String,
    private val parser: SkillParser,
) {
    open suspend fun load(): List<SkillSnapshot> = withContext(Dispatchers.IO) {
        assetManager.list(rootPath)
            .orEmpty()
            .sorted()
            .map { skillId ->
                val skillPath = "$rootPath/$skillId/SKILL.md"
                val document = runCatching {
                    assetManager.open(skillPath).bufferedReader().use { it.readText() }
                }.getOrElse { error ->
                    return@map SkillSnapshot(
                        id = skillId,
                        sourceType = SkillSourceType.Bundled,
                        baseDir = "asset://$rootPath/$skillId",
                        enabled = false,
                        frontmatter = null,
                        instructionsMd = "",
                        eligibility = SkillEligibility(
                            status = SkillEligibilityStatus.Invalid,
                            reasons = listOf(error.message ?: "Unable to open SKILL.md"),
                        ),
                        parseError = error.message,
                    )
                }

                when (val parsed = parser.parse(document)) {
                    is SkillParseResult.Success -> SkillSnapshot(
                        id = skillId,
                        sourceType = SkillSourceType.Bundled,
                        baseDir = "asset://$rootPath/$skillId",
                        enabled = true,
                        frontmatter = parsed.document.frontmatter,
                        instructionsMd = parsed.document.bodyMarkdown,
                        eligibility = SkillEligibility(status = SkillEligibilityStatus.Eligible),
                        rawFrontmatter = parsed.document.rawFrontmatter,
                    )

                    is SkillParseResult.Failure -> SkillSnapshot(
                        id = skillId,
                        sourceType = SkillSourceType.Bundled,
                        baseDir = "asset://$rootPath/$skillId",
                        enabled = false,
                        frontmatter = null,
                        instructionsMd = "",
                        eligibility = SkillEligibility(
                            status = SkillEligibilityStatus.Invalid,
                            reasons = listOf(parsed.error),
                        ),
                        parseError = parsed.error,
                    )
                }
            }
    }
}
