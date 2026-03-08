package ai.androidclaw.runtime.skills

import kotlinx.serialization.json.JsonElement

enum class SkillSourceType {
    Bundled,
    Local,
    Workspace,
}

enum class SkillCommandDispatch {
    Model,
    Tool,
}

enum class SkillEligibilityStatus {
    Eligible,
    Invalid,
    MissingTool,
    BridgeOnly,
}

data class SkillEligibility(
    val status: SkillEligibilityStatus,
    val reasons: List<String> = emptyList(),
)

data class SkillFrontmatter(
    val name: String,
    val description: String,
    val homepage: String?,
    val userInvocable: Boolean,
    val disableModelInvocation: Boolean,
    val commandDispatch: SkillCommandDispatch,
    val commandTool: String?,
    val commandArgMode: String,
    val metadata: JsonElement?,
    val unknownFields: Map<String, JsonElement>,
)

data class SkillSnapshot(
    val id: String,
    val sourceType: SkillSourceType,
    val baseDir: String,
    val enabled: Boolean,
    val frontmatter: SkillFrontmatter?,
    val instructionsMd: String,
    val eligibility: SkillEligibility,
    val parseError: String? = null,
    val rawFrontmatter: String? = null,
) {
    val displayName: String
        get() = frontmatter?.name ?: id
}

data class ParsedSkillDocument(
    val frontmatter: SkillFrontmatter,
    val bodyMarkdown: String,
    val rawFrontmatter: String,
)

sealed interface SkillParseResult {
    data class Success(
        val document: ParsedSkillDocument,
    ) : SkillParseResult

    data class Failure(
        val error: String,
    ) : SkillParseResult
}

