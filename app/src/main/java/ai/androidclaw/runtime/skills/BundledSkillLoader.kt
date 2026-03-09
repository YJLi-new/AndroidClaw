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
                    return@map invalidSkillSnapshot(
                        sourceId = buildSourceId(
                            sourceType = SkillSourceType.Bundled,
                            localId = skillId,
                        ),
                        sourceType = SkillSourceType.Bundled,
                        skillName = skillId,
                        baseDir = "asset://$rootPath/$skillId",
                        reason = error.message ?: "Unable to open SKILL.md",
                    )
                }
                parseSkillSnapshot(
                    parser = parser,
                    sourceId = buildSourceId(
                        sourceType = SkillSourceType.Bundled,
                        localId = skillId,
                    ),
                    sourceType = SkillSourceType.Bundled,
                    skillName = skillId,
                    baseDir = "asset://$rootPath/$skillId",
                    rawDocument = document,
                )
            }
    }
}
