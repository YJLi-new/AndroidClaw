package ai.androidclaw.runtime.skills

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileSkillLoader(
    private val parser: SkillParser,
) {
    suspend fun load(
        rootDir: File,
        sourceType: SkillSourceType,
        workspaceSessionId: String? = null,
    ): List<SkillSnapshot> = withContext(Dispatchers.IO) {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return@withContext emptyList()
        }
        rootDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedBy(File::getName)
            .mapNotNull { directory ->
                val skillFile = File(directory, "SKILL.md")
                if (!skillFile.isFile) {
                    return@mapNotNull null
                }
                val rawDocument = runCatching {
                    skillFile.bufferedReader().use { it.readText() }
                }.getOrElse { error ->
                    return@mapNotNull invalidSkillSnapshot(
                        sourceId = buildSourceId(
                            sourceType = sourceType,
                            localId = directory.name,
                            workspaceSessionId = workspaceSessionId,
                        ),
                        sourceType = sourceType,
                        skillName = directory.name,
                        workspaceSessionId = workspaceSessionId,
                        baseDir = directory.absolutePath,
                        reason = error.message ?: "Unable to read SKILL.md",
                    )
                }
                parseSkillSnapshot(
                    parser = parser,
                    sourceId = buildSourceId(
                        sourceType = sourceType,
                        localId = directory.name,
                        workspaceSessionId = workspaceSessionId,
                    ),
                    sourceType = sourceType,
                    skillName = directory.name,
                    workspaceSessionId = workspaceSessionId,
                    baseDir = directory.absolutePath,
                    rawDocument = rawDocument,
                )
            }
    }
}

internal fun buildSourceId(
    sourceType: SkillSourceType,
    localId: String,
    workspaceSessionId: String? = null,
): String {
    return when (sourceType) {
        SkillSourceType.Bundled -> "bundled:$localId"
        SkillSourceType.Local -> "local:$localId"
        SkillSourceType.Workspace -> "workspace:${workspaceSessionId.orEmpty()}:$localId"
    }
}

internal fun parseSkillSnapshot(
    parser: SkillParser,
    sourceId: String,
    sourceType: SkillSourceType,
    skillName: String,
    workspaceSessionId: String? = null,
    baseDir: String,
    rawDocument: String,
): SkillSnapshot {
    return when (val parsed = parser.parse(rawDocument)) {
        is SkillParseResult.Success -> SkillSnapshot(
            id = sourceId,
            skillKey = parsed.document.frontmatter.skillKey(),
            sourceType = sourceType,
            workspaceSessionId = workspaceSessionId,
            baseDir = baseDir,
            enabled = true,
            frontmatter = parsed.document.frontmatter,
            instructionsMd = parsed.document.bodyMarkdown,
            eligibility = SkillEligibility(status = SkillEligibilityStatus.Eligible),
            rawFrontmatter = parsed.document.rawFrontmatter,
        )

        is SkillParseResult.Failure -> invalidSkillSnapshot(
            sourceId = sourceId,
            sourceType = sourceType,
            skillName = skillName,
            workspaceSessionId = workspaceSessionId,
            baseDir = baseDir,
            reason = parsed.error,
        )
    }
}

internal fun invalidSkillSnapshot(
    sourceId: String,
    sourceType: SkillSourceType,
    skillName: String,
    workspaceSessionId: String? = null,
    baseDir: String,
    reason: String,
): SkillSnapshot {
    return SkillSnapshot(
        id = sourceId,
        skillKey = skillName,
        sourceType = sourceType,
        workspaceSessionId = workspaceSessionId,
        baseDir = baseDir,
        enabled = false,
        frontmatter = null,
        instructionsMd = "",
        eligibility = SkillEligibility(
            status = SkillEligibilityStatus.Invalid,
            reasons = listOf(reason),
        ),
        parseError = reason,
    )
}
