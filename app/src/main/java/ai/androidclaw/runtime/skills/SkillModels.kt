package ai.androidclaw.runtime.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class SkillSourceType {
    Bundled,
    Local,
    Workspace,
}

@Serializable
enum class SkillCommandDispatch {
    Model,
    Tool,
}

@Serializable
enum class SkillEligibilityStatus {
    Eligible,
    Invalid,
    MissingTool,
    BridgeOnly,
}

@Serializable
data class SkillEligibility(
    val status: SkillEligibilityStatus,
    val reasons: List<String> = emptyList(),
)

@Serializable
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
    val skillKey: String,
    val sourceType: SkillSourceType,
    val workspaceSessionId: String? = null,
    val baseDir: String,
    val enabled: Boolean,
    val frontmatter: SkillFrontmatter?,
    val instructionsMd: String,
    val eligibility: SkillEligibility,
    val parseError: String? = null,
    val rawFrontmatter: String? = null,
    val resolutionState: SkillResolutionState = SkillResolutionState.Effective,
    val shadowedBy: String? = null,
) {
    val displayName: String
        get() = frontmatter?.name ?: skillKey
}

enum class SkillResolutionState {
    Effective,
    Shadowed,
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
