package ai.androidclaw.runtime.tools

import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillConfigField
import ai.androidclaw.runtime.skills.SkillConfigurationSnapshot
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillFrontmatter
import ai.androidclaw.runtime.skills.SkillResolutionState
import ai.androidclaw.runtime.skills.SkillSecretField
import ai.androidclaw.runtime.skills.SkillSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val SKILL_INSTRUCTIONS_MAX_CHARS = 8_000
internal const val SKILL_COMMANDS_DEFAULT_LIMIT = 20
internal const val SKILL_COMMANDS_MAX_LIMIT = 100
internal const val SKILL_DOCTOR_DEFAULT_LIMIT = 20
internal const val SKILL_DOCTOR_MAX_LIMIT = 50
internal const val SKILL_DOCTOR_FIELD_LIST_LIMIT = 10
internal const val SKILL_DOCTOR_TEXT_MAX_CHARS = 500
internal const val SKILL_EXPORT_FORMAT = "androidclaw.skills.export.v1"
internal const val SKILL_EXPORT_VERSION = 1
internal const val SKILL_EXPORT_DEFAULT_LIMIT = 20
internal const val SKILL_EXPORT_MAX_LIMIT = 50
internal const val SKILL_EXPORT_INSTRUCTIONS_MAX_CHARS = 20_000
internal const val SKILL_IMPORT_FORMAT = "androidclaw.skills.import.v1"
internal const val SKILL_IMPORT_VERSION = 1
internal const val SKILL_IMPORT_DEFAULT_LIMIT = 20
internal const val SKILL_IMPORT_MAX_LIMIT = 50
internal const val SKILL_HANDOFF_DEFAULT_LIMIT = 8
internal const val SKILL_HANDOFF_MAX_LIMIT = 20
internal const val SKILL_SEARCH_DEFAULT_LIMIT = 20
internal const val SKILL_SEARCH_MAX_LIMIT = 50
internal const val SKILL_SEARCH_SNIPPET_MAX_CHARS = 500
internal const val SKILL_SETUP_MATRIX_DEFAULT_LIMIT = 20
internal const val SKILL_SETUP_MATRIX_MAX_LIMIT = 50

internal fun skillToggleDescriptor(
    name: String,
    aliases: List<String>,
    description: String,
): ToolDescriptor =
    ToolDescriptor(
        name = name,
        aliases = aliases,
        description = description,
        arguments =
            listOf(
                ToolArgumentSpec(
                    name = "skillId",
                    required = false,
                    description = "Skill id, key, or display name.",
                ),
            ),
    )

internal fun JsonObject.skillIdentifier(): String? = optionalText("skillId") ?: optionalText("id") ?: optionalText("name")

internal fun List<SkillSnapshot>.findByIdentifier(identifier: String): SkillSnapshot? =
    firstOrNull { candidate ->
        candidate.id.equals(identifier, ignoreCase = true) ||
            candidate.skillKey.equals(identifier, ignoreCase = true) ||
            candidate.displayName.equals(identifier, ignoreCase = true)
    }

internal fun invalidSkillArguments(
    toolName: String,
    summary: String,
    field: String = "skillId",
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = summary,
        errorCode = "INVALID_ARGUMENTS",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_ARGUMENTS")
                put("toolName", toolName)
                put("field", field)
            },
    )

internal fun skillNotFoundResult(
    toolName: String,
    skillId: String,
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Skill $skillId was not found.",
        errorCode = "SKILL_NOT_FOUND",
        payload =
            buildJsonObject {
                put("errorCode", "SKILL_NOT_FOUND")
                put("toolName", toolName)
                put("skillId", skillId)
            },
    )

internal fun skillConfigNotFoundResult(
    toolName: String,
    skillId: String,
    configPath: String,
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Config path $configPath was not declared for skill $skillId.",
        errorCode = "SKILL_CONFIG_NOT_FOUND",
        payload =
            buildJsonObject {
                put("errorCode", "SKILL_CONFIG_NOT_FOUND")
                put("toolName", toolName)
                put("skillId", skillId)
                put("configPath", configPath)
            },
    )

internal fun skillSecretNotFoundResult(
    toolName: String,
    skillId: String,
    envName: String,
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Secret $envName was not declared for skill $skillId.",
        errorCode = "SKILL_SECRET_NOT_FOUND",
        payload =
            buildJsonObject {
                put("errorCode", "SKILL_SECRET_NOT_FOUND")
                put("toolName", toolName)
                put("skillId", skillId)
                put("envName", envName)
            },
    )

internal fun SkillSnapshot.matchesSkillQuery(query: String): Boolean {
    val normalizedQuery = query.lowercase()
    return listOf(
        id,
        skillKey,
        displayName,
        frontmatter?.description.orEmpty(),
        frontmatter?.homepage.orEmpty(),
        frontmatter?.commandTool.orEmpty(),
        instructionsMd,
    ).any { value -> value.lowercase().contains(normalizedQuery) }
}

internal fun SkillSnapshot.toSkillSearchPayload(): JsonObject {
    val instructionSnippet =
        if (instructionsMd.length <= SKILL_SEARCH_SNIPPET_MAX_CHARS) {
            instructionsMd
        } else {
            instructionsMd.take(SKILL_SEARCH_SNIPPET_MAX_CHARS)
        }
    return buildJsonObject {
        put("id", id)
        put("skillKey", skillKey)
        put("name", displayName)
        put("enabled", enabled)
        put("sourceType", sourceType.name)
        put("eligibilityStatus", eligibility.status.name)
        put("description", frontmatter?.description?.let(::JsonPrimitive) ?: JsonNull)
        put("commandDispatch", frontmatter?.commandDispatch?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("commandTool", frontmatter?.commandTool?.let(::JsonPrimitive) ?: JsonNull)
        put("instructionsSnippet", instructionSnippet)
        put("instructionsLength", instructionsMd.length)
        put("instructionsTruncated", instructionSnippet.length < instructionsMd.length)
    }
}

internal fun SkillSnapshot.toSkillHandoffPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("skillKey", skillKey)
        put("name", displayName)
        put("enabled", enabled)
        put("sourceType", sourceType.name)
        put("workspaceSessionId", workspaceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("resolutionState", resolutionState.name)
        put("shadowedBy", shadowedBy?.let(::JsonPrimitive) ?: JsonNull)
        put("eligibilityStatus", eligibility.status.name)
        put(
            "eligibilityReasons",
            buildJsonArray {
                eligibility.reasons.forEach { reason -> add(JsonPrimitive(reason)) }
            },
        )
        put("description", frontmatter?.description?.let(::JsonPrimitive) ?: JsonNull)
        put("userInvocable", frontmatter?.userInvocable?.let(::JsonPrimitive) ?: JsonNull)
        put("disableModelInvocation", frontmatter?.disableModelInvocation?.let(::JsonPrimitive) ?: JsonNull)
        put("commandDispatch", frontmatter?.commandDispatch?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("commandTool", frontmatter?.commandTool?.let(::JsonPrimitive) ?: JsonNull)
        put("secretFieldCount", secretStatuses.size)
        put("missingSecretFieldCount", secretStatuses.count { (_, configured) -> !configured })
        put("configFieldCount", configStatuses.size)
        put("missingConfigFieldCount", configStatuses.count { (_, configured) -> !configured })
        put("parseError", parseError?.let(::JsonPrimitive) ?: JsonNull)
        put("instructionsLength", instructionsMd.length)
        put("instructionsOmitted", true)
    }

internal fun List<SkillSnapshot>.toSkillHandoffMarkdown(
    totalSkillCount: Int,
    candidateSkillCount: Int,
    limit: Int,
    includeDisabled: Boolean,
): String {
    val includedSkills = this
    return buildString {
        appendLine("# Skills handoff")
        appendLine()
        appendLine("- Skills in inventory: $totalSkillCount")
        appendLine("- Candidate skills after filters: $candidateSkillCount")
        appendLine("- Skills included: ${includedSkills.size} of up to $limit")
        appendLine("- Disabled skills included: $includeDisabled")
        appendLine()
        appendLine("## Included skills")
        if (includedSkills.isEmpty()) {
            appendLine("_No skills included._")
        } else {
            includedSkills.forEach { skill ->
                appendLine(skill.toSkillHandoffMarkdownLine())
            }
        }
    }
}

internal fun SkillSnapshot.toSkillHandoffMarkdownLine(): String =
    buildString {
        append("- `")
        append(displayName.toHandoffLine())
        append("` id=`")
        append(id.toHandoffLine())
        append("` enabled=")
        append(enabled)
        append(" source=")
        append(sourceType.name)
        append(" eligibility=")
        append(eligibility.status.name)
        append(" resolution=")
        append(resolutionState.name)
        frontmatter?.commandDispatch?.let { dispatch ->
            append(" dispatch=")
            append(dispatch.name)
        }
        frontmatter?.commandTool?.let { toolName ->
            append(" tool=`")
            append(toolName.toHandoffLine())
            append("`")
        }
        frontmatter?.description?.let { description ->
            append(" - ")
            append(description.toHandoffLine())
        }
        parseError?.let { error ->
            append(" parseError=")
            append(error.toHandoffLine())
        }
    }

internal fun JsonObject.skillImportEntries(): SkillImportEntriesParseResult {
    val directEntries = this["skills"]
    val exportEntries = (this["export"] as? JsonObject)?.get("skills")
    val payloadEntries = (this["payload"] as? JsonObject)?.get("skills")
    val entries =
        directEntries ?: exportEntries ?: payloadEntries ?: return SkillImportEntriesParseResult.Failure(
            missingSkillImportEntriesResult(),
        )
    return (entries as? JsonArray)?.let(SkillImportEntriesParseResult::Success)
        ?: SkillImportEntriesParseResult.Failure(invalidSkillImportEntriesResult())
}

internal fun JsonElement.toSkillImportCandidate(sourceIndex: Int): SkillImportCandidateParseResult {
    val objectValue =
        this as? JsonObject ?: return skillImportSkipped(
            sourceIndex = sourceIndex,
            code = "skills.import.invalid_entry",
            summary = "Import entry must be a skill object.",
        )
    val frontmatterObject =
        objectValue["frontmatter"] as? JsonObject ?: return skillImportSkipped(
            sourceIndex = sourceIndex,
            code = "skills.import.invalid_missing_frontmatter",
            summary = "Import entry skipped because frontmatter is missing.",
        )
    val frontmatter =
        frontmatterObject.toSkillImportFrontmatter()
            ?: return skillImportSkipped(
                sourceIndex = sourceIndex,
                code = "skills.import.invalid_frontmatter",
                summary = "Import entry skipped because frontmatter is incomplete or invalid.",
            )
    val instructionsElement = objectValue["instructionsMd"]
    val instructionsMd =
        if (instructionsElement == null || instructionsElement is JsonNull) {
            return skillImportSkipped(
                sourceIndex = sourceIndex,
                code = "skills.import.invalid_missing_instructions",
                summary = "Import entry skipped because SKILL.md instructions are missing.",
            )
        } else {
            val primitive =
                instructionsElement as? JsonPrimitive ?: return skillImportSkipped(
                    sourceIndex = sourceIndex,
                    code = "skills.import.invalid_instructions",
                    summary = "Import entry skipped because SKILL.md instructions are not text.",
                )
            primitive.contentOrNull.orEmpty()
        }
    val configValues = objectValue.skillImportConfigValues()
    return SkillImportCandidateParseResult.Candidate(
        SkillImportCandidate(
            sourceIndex = sourceIndex,
            sourceSkillId = objectValue.optionalText("id") ?: objectValue.optionalText("skillId"),
            sourceSkillKey = objectValue.optionalText("skillKey") ?: frontmatter.name,
            sourceEnabled = objectValue.optionalBoolean("enabled", defaultValue = true),
            frontmatter = frontmatter,
            instructionsMd = instructionsMd,
            configValues = configValues,
        ),
    )
}

internal fun JsonObject.toSkillImportFrontmatter(): SkillFrontmatter? {
    val name = optionalText("name") ?: return null
    val description = optionalText("description") ?: return null
    val rawDispatch = optionalText("commandDispatch") ?: optionalText("command-dispatch") ?: "Model"
    val commandDispatch =
        when (rawDispatch.lowercase().replace("-", "_")) {
            "model" -> SkillCommandDispatch.Model
            "tool" -> SkillCommandDispatch.Tool
            else -> return null
        }
    return SkillFrontmatter(
        name = name,
        description = description,
        homepage = optionalRawText("homepage"),
        userInvocable =
            optionalBoolean(
                field = "userInvocable",
                defaultValue = optionalBoolean("user-invocable", defaultValue = true),
            ),
        disableModelInvocation =
            optionalBoolean(
                field = "disableModelInvocation",
                defaultValue = optionalBoolean("disable-model-invocation", defaultValue = false),
            ),
        commandDispatch = commandDispatch,
        commandTool = optionalRawText("commandTool") ?: optionalRawText("command-tool"),
        commandArgMode = optionalText("commandArgMode") ?: optionalText("command-arg-mode") ?: "raw",
        metadata = this["metadata"]?.takeUnless { it is JsonNull },
        unknownFields =
            (this["unknownFields"] as? JsonObject)
                ?.filterKeys { field -> field.isNotBlank() }
                .orEmpty(),
    )
}

internal fun JsonObject.skillImportConfigValues(): Map<String, String?> {
    val configuration = this["configuration"] as? JsonObject ?: return emptyMap()
    val fields = configuration["configFields"] as? JsonArray ?: return emptyMap()
    return fields
        .mapNotNull { fieldElement ->
            val field = fieldElement as? JsonObject ?: return@mapNotNull null
            val path = field.optionalText("path") ?: return@mapNotNull null
            val valueElement = field["value"]
            val value =
                when (valueElement) {
                    null,
                    JsonNull,
                    -> null
                    is JsonPrimitive -> valueElement.contentOrNull
                    else -> null
                }
            if (value == null) {
                null
            } else {
                path to value
            }
        }.toMap()
}

internal fun skillImportSkipped(
    sourceIndex: Int,
    code: String,
    summary: String,
): SkillImportCandidateParseResult.Skipped =
    SkillImportCandidateParseResult.Skipped(
        SkillImportSkippedEntry(
            sourceIndex = sourceIndex,
            code = code,
            summary = summary,
        ),
    )

internal fun missingSkillImportConfirmationResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Pass confirm=CONFIRM to import skills, or dryRun=true to preview without writing.",
        errorCode = "MISSING_SKILL_IMPORT_CONFIRMATION",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_SKILL_IMPORT_CONFIRMATION")
                put("field", "confirm")
            },
    )

internal fun missingSkillImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provide a skills array or an export object containing skills to import.",
        errorCode = "MISSING_SKILL_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_SKILL_IMPORT_ENTRIES")
                put("field", "skills")
            },
    )

internal fun invalidSkillImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Skill import entries must be an array.",
        errorCode = "INVALID_SKILL_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_SKILL_IMPORT_ENTRIES")
                put("field", "skills")
            },
    )

internal fun SkillImportCandidate.toSkillImportCandidatePayload(): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("sourceSkillId", sourceSkillId?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceSkillKey", sourceSkillKey)
        put("sourceEnabled", sourceEnabled)
        put("skillKey", sourceSkillKey)
        put("name", frontmatter.name)
        put("description", frontmatter.description)
        put("userInvocable", frontmatter.userInvocable)
        put("disableModelInvocation", frontmatter.disableModelInvocation)
        put("commandDispatch", frontmatter.commandDispatch.name)
        put("commandTool", frontmatter.commandTool?.let(::JsonPrimitive) ?: JsonNull)
        put("commandArgMode", frontmatter.commandArgMode)
        put("instructionsLength", instructionsMd.length)
        put("instructionsOmitted", true)
        put("configValueCount", configValues.size)
        put("secretValuesImported", false)
        put("secretValuesIncluded", false)
        put("rawFrontmatterImported", false)
        put("baseDirImported", false)
    }

internal fun SkillImportCandidate.toSkillImportedPayload(
    enableImported: Boolean,
    importConfigValues: Boolean,
    replacedSkillNames: List<String>,
): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("sourceSkillId", sourceSkillId?.let(::JsonPrimitive) ?: JsonNull)
        put("skillKey", sourceSkillKey)
        put("name", frontmatter.name)
        put("enabled", enableImported && sourceEnabled)
        put("sourceEnabled", sourceEnabled)
        put("replaced", frontmatter.name in replacedSkillNames)
        put("configValuesImported", importConfigValues)
        put("configValueCount", if (importConfigValues) configValues.size else 0)
        put("instructionsLength", instructionsMd.length)
        put("instructionsOmitted", true)
        put("secretValuesImported", false)
        put("secretValuesIncluded", false)
        put("rawFrontmatterImported", false)
        put("baseDirImported", false)
    }

internal fun SkillImportSkippedEntry.toSkillImportSkippedPayload(): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("code", code)
        put("summary", summary)
    }

internal fun SkillSnapshot.toSkillExportPayload(
    includeInstructions: Boolean,
    configuration: SkillConfigurationSnapshot?,
): JsonObject {
    val instructionsSnippet =
        if (instructionsMd.length <= SKILL_EXPORT_INSTRUCTIONS_MAX_CHARS) {
            instructionsMd
        } else {
            instructionsMd.take(SKILL_EXPORT_INSTRUCTIONS_MAX_CHARS)
        }
    return buildJsonObject {
        put("id", id)
        put("skillKey", skillKey)
        put("name", displayName)
        put("enabled", enabled)
        put("sourceType", sourceType.name)
        put("workspaceSessionId", workspaceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("resolutionState", resolutionState.name)
        put("shadowedBy", shadowedBy?.let(::JsonPrimitive) ?: JsonNull)
        put("eligibilityStatus", eligibility.status.name)
        put(
            "eligibilityReasons",
            buildJsonArray {
                eligibility.reasons.forEach { reason -> add(JsonPrimitive(reason)) }
            },
        )
        put("parseError", parseError?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "frontmatter",
            frontmatter?.let { metadata ->
                buildJsonObject {
                    put("name", metadata.name)
                    put("description", metadata.description)
                    put("homepage", metadata.homepage?.let(::JsonPrimitive) ?: JsonNull)
                    put("userInvocable", metadata.userInvocable)
                    put("disableModelInvocation", metadata.disableModelInvocation)
                    put("commandDispatch", metadata.commandDispatch.name)
                    put("commandTool", metadata.commandTool?.let(::JsonPrimitive) ?: JsonNull)
                    put("commandArgMode", metadata.commandArgMode)
                    put("metadata", metadata.metadata ?: JsonNull)
                    put(
                        "unknownFields",
                        buildJsonObject {
                            metadata.unknownFields.forEach { (field, value) ->
                                put(field, value)
                            }
                        },
                    )
                }
            } ?: JsonNull,
        )
        put(
            "secretStatuses",
            buildJsonArray {
                secretStatuses.forEach { (envName, configured) ->
                    add(
                        buildJsonObject {
                            put("envName", envName)
                            put("configured", configured)
                        },
                    )
                }
            },
        )
        put(
            "configStatuses",
            buildJsonArray {
                configStatuses.forEach { (path, configured) ->
                    add(
                        buildJsonObject {
                            put("path", path)
                            put("configured", configured)
                        },
                    )
                }
            },
        )
        put("configuration", configuration?.toSkillConfigurationPayload() ?: JsonNull)
        put("configValuesIncluded", configuration != null)
        put("secretValuesIncluded", false)
        put("secretValuesOmitted", true)
        put("baseDirIncluded", false)
        put("baseDir", JsonNull)
        put("rawFrontmatterIncluded", false)
        put("rawFrontmatter", JsonNull)
        put("instructionsIncluded", includeInstructions)
        put("instructionsLength", instructionsMd.length)
        put("instructionsTruncated", includeInstructions && instructionsSnippet.length < instructionsMd.length)
        put("instructionsMd", if (includeInstructions) JsonPrimitive(instructionsSnippet) else JsonNull)
    }
}

internal fun List<SkillSnapshot>.toSkillExportMarkdown(
    totalSkillCount: Int,
    candidateSkillCount: Int,
    limit: Int,
    includeDisabled: Boolean,
    includeInstructions: Boolean,
    includeConfigValues: Boolean,
): String {
    val includedSkills = this
    return buildString {
        appendLine("# Skills export")
        appendLine()
        appendLine("- Skills in inventory: $totalSkillCount")
        appendLine("- Candidate skills after filters: $candidateSkillCount")
        appendLine("- Skills included: ${includedSkills.size} of up to $limit")
        appendLine("- Disabled skills included: $includeDisabled")
        appendLine("- SKILL.md instruction bodies included: $includeInstructions")
        appendLine("- Non-secret config values included: $includeConfigValues")
        appendLine("- Secret values included: false")
        appendLine("- Base directories included: false")
        appendLine()
        appendLine("## Exported skills")
        if (includedSkills.isEmpty()) {
            appendLine("_No skills included._")
        } else {
            includedSkills.forEach { skill ->
                appendLine(
                    skill.toSkillExportMarkdownLine(
                        includeInstructions = includeInstructions,
                        includeConfigValues = includeConfigValues,
                    ),
                )
            }
        }
    }
}

internal fun SkillSnapshot.toSkillExportMarkdownLine(
    includeInstructions: Boolean,
    includeConfigValues: Boolean,
): String =
    buildString {
        append("- `")
        append(displayName.toHandoffLine())
        append("` id=`")
        append(id.toHandoffLine())
        append("` key=`")
        append(skillKey.toHandoffLine())
        append("` enabled=")
        append(enabled)
        append(" source=")
        append(sourceType.name)
        append(" eligibility=")
        append(eligibility.status.name)
        append(" resolution=")
        append(resolutionState.name)
        append(" instructionsIncluded=")
        append(includeInstructions)
        append(" configValuesIncluded=")
        append(includeConfigValues)
        frontmatter?.commandDispatch?.let { dispatch ->
            append(" dispatch=")
            append(dispatch.name)
        }
        frontmatter?.commandTool?.let { toolName ->
            append(" tool=`")
            append(toolName.toHandoffLine())
            append("`")
        }
        append(" secretFields=")
        append(secretStatuses.size)
        append(" configFields=")
        append(configStatuses.size)
        parseError?.let { error ->
            append(" parseError=")
            append(error.toHandoffLine())
        }
    }

internal fun SkillSnapshot.isSkillCommandInvocable(): Boolean {
    val frontmatter = frontmatter ?: return false
    return enabled &&
        frontmatter.userInvocable &&
        resolutionState == SkillResolutionState.Effective &&
        eligibility.status == SkillEligibilityStatus.Eligible
}

internal fun SkillSnapshot.isSkillCommandModelReady(): Boolean {
    val frontmatter = frontmatter ?: return false
    return isSkillCommandInvocable() &&
        frontmatter.commandDispatch == SkillCommandDispatch.Model &&
        !frontmatter.disableModelInvocation
}

internal fun SkillSnapshot.toSkillCommandPayload(duplicateCommandCount: Int): JsonObject {
    val frontmatter = requireNotNull(frontmatter)
    return buildJsonObject {
        put("commandName", frontmatter.name)
        put("slashCommand", "/${frontmatter.name}")
        put("skillId", id)
        put("skillKey", skillKey)
        put("name", displayName)
        put("enabled", enabled)
        put("sourceType", sourceType.name)
        put("workspaceSessionId", workspaceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("resolutionState", resolutionState.name)
        put("shadowedBy", shadowedBy?.let(::JsonPrimitive) ?: JsonNull)
        put("eligibilityStatus", eligibility.status.name)
        put(
            "eligibilityReasons",
            buildJsonArray {
                eligibility.reasons.forEach { reason -> add(JsonPrimitive(reason)) }
            },
        )
        put("userInvocable", frontmatter.userInvocable)
        put("invocable", isSkillCommandInvocable())
        put("modelReady", isSkillCommandModelReady())
        put("disableModelInvocation", frontmatter.disableModelInvocation)
        put("commandDispatch", frontmatter.commandDispatch.name)
        put("commandTool", frontmatter.commandTool?.let(::JsonPrimitive) ?: JsonNull)
        put("commandArgMode", frontmatter.commandArgMode)
        put("duplicateCommandCount", duplicateCommandCount)
        put("description", frontmatter.description)
        put("secretFieldCount", secretStatuses.size)
        put("missingSecretFieldCount", secretStatuses.count { (_, configured) -> !configured })
        put("configFieldCount", configStatuses.size)
        put("missingConfigFieldCount", configStatuses.count { (_, configured) -> !configured })
        put("parseError", parseError?.let(::JsonPrimitive) ?: JsonNull)
        put("instructionsLength", instructionsMd.length)
        put("instructionsOmitted", true)
        put("secretValuesOmitted", true)
    }
}

internal fun List<SkillSnapshot>.toSkillCommandsMarkdown(
    totalSkillCount: Int,
    declaredCommandCount: Int,
    candidateCommandCount: Int,
    limit: Int,
    includeDisabled: Boolean,
    includeNonInvocable: Boolean,
    commandNameCounts: Map<String, Int>,
): String {
    val includedCommands = this
    return buildString {
        appendLine("# Skill commands")
        appendLine()
        appendLine("- Skills in inventory: $totalSkillCount")
        appendLine("- Skills with command metadata: $declaredCommandCount")
        appendLine("- Candidate commands after filters: $candidateCommandCount")
        appendLine("- Commands included: ${includedCommands.size} of up to $limit")
        appendLine("- Disabled skills included: $includeDisabled")
        appendLine("- Non-user-invocable skills included: $includeNonInvocable")
        appendLine("- SKILL.md instruction bodies omitted: true")
        appendLine("- Secret values omitted: true")
        appendLine()
        appendLine("## Commands")
        if (includedCommands.isEmpty()) {
            appendLine("_No skill commands included._")
        } else {
            includedCommands.forEach { skill ->
                val commandName = skill.frontmatter?.name.orEmpty()
                appendLine(
                    skill.toSkillCommandMarkdownLine(
                        duplicateCommandCount = commandNameCounts[commandName] ?: 1,
                    ),
                )
            }
        }
    }
}

internal fun SkillSnapshot.toSkillCommandMarkdownLine(duplicateCommandCount: Int): String {
    val frontmatter = requireNotNull(frontmatter)
    return buildString {
        append("- `/")
        append(frontmatter.name.toHandoffLine())
        append("` skill=`")
        append(displayName.toHandoffLine())
        append("` id=`")
        append(id.toHandoffLine())
        append("` enabled=")
        append(enabled)
        append(" userInvocable=")
        append(frontmatter.userInvocable)
        append(" invocable=")
        append(isSkillCommandInvocable())
        append(" dispatch=")
        append(frontmatter.commandDispatch.name)
        frontmatter.commandTool?.let { toolName ->
            append(" tool=`")
            append(toolName.toHandoffLine())
            append("`")
        }
        append(" argMode=")
        append(frontmatter.commandArgMode.toHandoffLine())
        append(" eligibility=")
        append(eligibility.status.name)
        if (duplicateCommandCount > 1) {
            append(" duplicateCount=")
            append(duplicateCommandCount)
        }
    }
}

internal fun SkillSnapshot.toSkillCommandExamplePayload(
    requestedCommand: String,
    duplicateCommandCount: Int,
    includeMarkdown: Boolean,
    exampleMarkdown: String?,
): JsonObject {
    val frontmatter = requireNotNull(frontmatter)
    return buildJsonObject {
        put("requestedCommand", requestedCommand)
        put("commandName", frontmatter.name)
        put("slashCommand", "/${frontmatter.name}")
        put("exampleInvocation", frontmatter.toSkillCommandExampleInvocation())
        put("exampleArgument", frontmatter.toSkillCommandExampleArgument())
        put("exampleOnly", true)
        put("executesCommand", false)
        put("includeMarkdown", includeMarkdown)
        put("skillId", id)
        put("skillKey", skillKey)
        put("name", displayName)
        put("enabled", enabled)
        put("sourceType", sourceType.name)
        put("workspaceSessionId", workspaceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("resolutionState", resolutionState.name)
        put("eligibilityStatus", eligibility.status.name)
        put("userInvocable", frontmatter.userInvocable)
        put("invocable", isSkillCommandInvocable())
        put("modelReady", isSkillCommandModelReady())
        put("commandDispatch", frontmatter.commandDispatch.name)
        put("commandTool", frontmatter.commandTool?.let(::JsonPrimitive) ?: JsonNull)
        put("commandArgMode", frontmatter.commandArgMode)
        put("toolDispatchReady", frontmatter.commandDispatch == SkillCommandDispatch.Tool && !frontmatter.commandTool.isNullOrBlank())
        put("suggestedToolExample", "tools.example")
        put("suggestedToolName", frontmatter.commandTool?.let(::JsonPrimitive) ?: JsonNull)
        put("duplicateCommandCount", duplicateCommandCount)
        put("description", frontmatter.description)
        put("instructionsOmitted", true)
        put("secretValuesIncluded", false)
        put("secretValuesOmitted", true)
        put("exampleMarkdown", exampleMarkdown?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal fun SkillSnapshot.toSkillCommandExampleMarkdown(duplicateCommandCount: Int): String {
    val frontmatter = requireNotNull(frontmatter)
    return buildString {
        appendLine("# Skill command example")
        appendLine()
        appendLine("- Command: `/${frontmatter.name.toHandoffLine()}`")
        appendLine("- Skill: `${displayName.toHandoffLine()}`")
        appendLine("- Dispatch: ${frontmatter.commandDispatch.name}")
        appendLine("- Argument mode: ${frontmatter.commandArgMode.toHandoffLine()}")
        appendLine("- Invocable now: ${isSkillCommandInvocable()}")
        appendLine("- Example only: true")
        appendLine("- Executes command: false")
        appendLine("- SKILL.md instructions omitted: true")
        appendLine("- Secret values included: false")
        if (duplicateCommandCount > 1) {
            appendLine("- Duplicate command count: $duplicateCommandCount")
        }
        frontmatter.commandTool?.let { toolName ->
            appendLine("- Tool-dispatch target: `$toolName`")
            appendLine("- Suggested next step: `tools.example` for `$toolName`")
        }
        appendLine()
        appendLine("```text")
        appendLine(frontmatter.toSkillCommandExampleInvocation())
        appendLine("```")
    }
}

internal fun SkillFrontmatter.toSkillCommandExampleInvocation(): String {
    val argument = toSkillCommandExampleArgument()
    return if (argument.isBlank()) {
        "/$name"
    } else {
        "/$name $argument"
    }
}

internal fun SkillFrontmatter.toSkillCommandExampleArgument(): String =
    when (commandArgMode.trim().lowercase()) {
        "none", "empty", "no_args", "no-args" -> ""
        "json" -> "{\"input\":\"Example request\"}"
        "tool", "object" -> "{\"query\":\"Example request\"}"
        else -> "Example request"
    }

internal fun SkillSnapshot.toSkillDoctorIssues(): List<SkillDoctorIssue> =
    buildList {
        fun addIssue(
            severity: String,
            code: String,
            summary: String,
            action: String,
            parseErrorOverride: String? = null,
            missingSecretNames: List<String> = emptyList(),
            missingConfigPaths: List<String> = emptyList(),
        ) {
            val includedMissingSecretNames = missingSecretNames.take(SKILL_DOCTOR_FIELD_LIST_LIMIT)
            val includedMissingConfigPaths = missingConfigPaths.take(SKILL_DOCTOR_FIELD_LIST_LIMIT)
            add(
                SkillDoctorIssue(
                    id = "$id:$code",
                    severity = severity,
                    code = code,
                    skillId = id,
                    skillKey = skillKey,
                    displayName = displayName,
                    sourceType = sourceType.name,
                    enabled = enabled,
                    resolutionState = resolutionState.name,
                    eligibilityStatus = eligibility.status.name,
                    summary = summary.toSkillDoctorText(),
                    action = action.toSkillDoctorText(),
                    parseError = parseErrorOverride?.toSkillDoctorText(),
                    missingSecretNames = includedMissingSecretNames.map { name -> name.toSkillDoctorText() },
                    omittedMissingSecretNameCount =
                        (missingSecretNames.size - includedMissingSecretNames.size).coerceAtLeast(0),
                    missingConfigPaths = includedMissingConfigPaths.map { path -> path.toSkillDoctorText() },
                    omittedMissingConfigPathCount =
                        (missingConfigPaths.size - includedMissingConfigPaths.size).coerceAtLeast(0),
                ),
            )
        }

        if (parseError != null || frontmatter == null) {
            val reason = parseError ?: "No parsed frontmatter was available."
            addIssue(
                severity = "Error",
                code = if (parseError != null) "skill.parse_error" else "skill.frontmatter.missing",
                summary = "Skill $displayName has invalid SKILL.md metadata: $reason",
                action = "Fix SKILL.md frontmatter, then reimport or rescan the skill.",
                parseErrorOverride = parseError,
            )
        }
        if (resolutionState == SkillResolutionState.Shadowed) {
            addIssue(
                severity = "Warning",
                code = "skill.shadowed",
                summary = "Skill $displayName is shadowed by ${shadowedBy ?: "another skill"}.",
                action = "Remove or rename the duplicate skill if this definition should be effective.",
            )
        }
        if (!enabled && eligibility.status == SkillEligibilityStatus.Eligible && parseError == null) {
            addIssue(
                severity = "Warning",
                code = "skill.disabled",
                summary = "Skill $displayName is disabled and will not be invoked.",
                action = "Run skills.enable with this skillId if the skill should be invocable.",
            )
        }
        if (eligibility.status != SkillEligibilityStatus.Eligible) {
            addIssue(
                severity = eligibility.status.toSkillDoctorSeverity(),
                code = "skill.ineligible.${eligibility.status.name.lowercase()}",
                summary =
                    "Skill $displayName is ${eligibility.status.name}: " +
                        eligibility.reasons.toSkillDoctorReasonText(),
                action = eligibility.status.toSkillDoctorAction(),
            )
        }
        val missingSecretNames = secretStatuses.filterValues { configured -> !configured }.keys.sorted()
        if (missingSecretNames.isNotEmpty()) {
            addIssue(
                severity = "Warning",
                code = "skill.secrets.missing",
                summary = "Skill $displayName is missing ${missingSecretNames.size} required secret value(s).",
                action = "Set the required skill secrets before invoking this skill.",
                missingSecretNames = missingSecretNames,
            )
        }
        val missingConfigPaths = configStatuses.filterValues { configured -> !configured }.keys.sorted()
        if (missingConfigPaths.isNotEmpty()) {
            addIssue(
                severity = "Warning",
                code = "skill.config.missing",
                summary = "Skill $displayName is missing ${missingConfigPaths.size} required config value(s).",
                action = "Set the required skill config values before invoking this skill.",
                missingConfigPaths = missingConfigPaths,
            )
        }
        val frontmatter = frontmatter
        if (frontmatter != null) {
            if (frontmatter.commandDispatch == SkillCommandDispatch.Tool && frontmatter.commandTool.isNullOrBlank()) {
                addIssue(
                    severity = "Error",
                    code = "skill.tool_dispatch.missing_tool",
                    summary = "Skill $displayName uses tool dispatch but does not declare command_tool.",
                    action = "Declare command_tool or switch command_dispatch to model.",
                )
            }
            if (
                frontmatter.commandDispatch == SkillCommandDispatch.Model &&
                frontmatter.disableModelInvocation
            ) {
                addIssue(
                    severity = "Warning",
                    code = "skill.model_invocation.disabled",
                    summary = "Skill $displayName uses model dispatch but disables model invocation.",
                    action = "Enable model invocation or switch the skill to tool dispatch.",
                )
            }
        }
    }

internal fun SkillEligibilityStatus.toSkillDoctorSeverity(): String =
    when (this) {
        SkillEligibilityStatus.Eligible -> "Info"
        SkillEligibilityStatus.Invalid,
        SkillEligibilityStatus.MissingTool,
        -> "Error"
        SkillEligibilityStatus.BridgeOnly -> "Warning"
    }

internal fun SkillEligibilityStatus.toSkillDoctorAction(): String =
    when (this) {
        SkillEligibilityStatus.Eligible -> "No action required."
        SkillEligibilityStatus.Invalid -> "Fix skill metadata and reimport or rescan the skill."
        SkillEligibilityStatus.MissingTool -> "Install or enable the declared command tool, or update command_tool."
        SkillEligibilityStatus.BridgeOnly -> "Run from an environment that provides the bridge capability or disable the skill."
    }

internal fun List<String>.toSkillDoctorReasonText(): String {
    val includedReasons = take(3).map { reason -> reason.toSkillDoctorText() }
    val reasonText = includedReasons.joinToString("; ").ifBlank { "No reason provided." }
    return if (size > includedReasons.size) {
        "$reasonText; +${size - includedReasons.size} more"
    } else {
        reasonText
    }
}

internal fun String.toSkillDoctorText(): String = toHandoffLine().take(SKILL_DOCTOR_TEXT_MAX_CHARS)

internal fun List<SkillDoctorIssue>.toSkillDoctorStatus(): String =
    when {
        any { issue -> issue.severity == "Error" } -> "ERROR"
        any { issue -> issue.severity == "Warning" } -> "WARN"
        else -> "OK"
    }

internal fun SkillDoctorIssue.toSkillDoctorPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("severity", severity)
        put("code", code)
        put("skillId", skillId)
        put("skillKey", skillKey)
        put("name", displayName)
        put("sourceType", sourceType)
        put("enabled", enabled)
        put("resolutionState", resolutionState)
        put("eligibilityStatus", eligibilityStatus)
        put("summary", summary)
        put("action", action)
        put("parseError", parseError?.let(::JsonPrimitive) ?: JsonNull)
        put("missingSecretNameCount", missingSecretNames.size + omittedMissingSecretNameCount)
        put("omittedMissingSecretNameCount", omittedMissingSecretNameCount)
        put(
            "missingSecretNames",
            buildJsonArray {
                missingSecretNames.forEach { name -> add(JsonPrimitive(name)) }
            },
        )
        put("missingConfigPathCount", missingConfigPaths.size + omittedMissingConfigPathCount)
        put("omittedMissingConfigPathCount", omittedMissingConfigPathCount)
        put(
            "missingConfigPaths",
            buildJsonArray {
                missingConfigPaths.forEach { path -> add(JsonPrimitive(path)) }
            },
        )
    }

internal fun List<SkillDoctorIssue>.toSkillDoctorMarkdown(
    status: String,
    totalSkillCount: Int,
    candidateSkillCount: Int,
    issueCount: Int,
    limit: Int,
    includeDisabled: Boolean,
): String {
    val includedIssues = this
    return buildString {
        appendLine("# Skills doctor")
        appendLine()
        appendLine("- Status: $status")
        appendLine("- Skills in inventory: $totalSkillCount")
        appendLine("- Candidate skills after filters: $candidateSkillCount")
        appendLine("- Issues included: ${includedIssues.size} of $issueCount")
        appendLine("- Limit: $limit")
        appendLine("- Disabled skills included: $includeDisabled")
        appendLine("- SKILL.md instruction bodies omitted: true")
        appendLine()
        appendLine("## Issues")
        if (includedIssues.isEmpty()) {
            appendLine("_No skill issues found._")
        } else {
            includedIssues.forEach { issue ->
                appendLine(issue.toSkillDoctorMarkdownLine())
            }
        }
    }
}

internal fun SkillDoctorIssue.toSkillDoctorMarkdownLine(): String =
    buildString {
        append("- ")
        append(severity)
        append(" `")
        append(displayName.toHandoffLine())
        append("` id=`")
        append(skillId.toHandoffLine())
        append("` code=")
        append(code)
        append(": ")
        append(summary.toHandoffLine())
        if (missingSecretNames.isNotEmpty()) {
            append(" missingSecrets=")
            append(missingSecretNames.joinToString(",") { name -> name.toHandoffLine() })
            if (omittedMissingSecretNameCount > 0) {
                append(",+")
                append(omittedMissingSecretNameCount)
                append(" more")
            }
        }
        if (missingConfigPaths.isNotEmpty()) {
            append(" missingConfig=")
            append(missingConfigPaths.joinToString(",") { path -> path.toHandoffLine() })
            if (omittedMissingConfigPathCount > 0) {
                append(",+")
                append(omittedMissingConfigPathCount)
                append(" more")
            }
        }
        append(" Action: ")
        append(action.toHandoffLine())
    }

internal data class SkillSetupRequirement(
    val code: String,
    val severity: String,
    val field: String?,
    val summary: String,
    val action: String,
    val suggestedTool: String,
)

internal data class SkillSetupReadinessEntry(
    val skill: SkillSnapshot,
    val configuration: SkillConfigurationSnapshot,
    val requirements: List<SkillSetupRequirement>,
) {
    val setupStatus: String
        get() = requirements.toSkillSetupStatus()

    val readyForUse: Boolean
        get() = requirements.isEmpty()
}

internal fun SkillSnapshot.toSkillSetupPayload(
    configuration: SkillConfigurationSnapshot,
    requirements: List<SkillSetupRequirement>,
    requestedSkillId: String,
    includeMarkdown: Boolean,
    setupMarkdown: String?,
): JsonObject =
    buildJsonObject {
        put("requestedSkillId", requestedSkillId)
        put("skillId", id)
        put("skillKey", skillKey)
        put("name", displayName)
        put("enabled", enabled)
        put("sourceType", sourceType.name)
        put("workspaceSessionId", workspaceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("resolutionState", resolutionState.name)
        put("shadowedBy", shadowedBy?.let(::JsonPrimitive) ?: JsonNull)
        put("eligibilityStatus", eligibility.status.name)
        put(
            "eligibilityReasons",
            buildJsonArray {
                eligibility.reasons.forEach { reason -> add(JsonPrimitive(reason)) }
            },
        )
        put("hasFrontmatter", frontmatter != null)
        put("parseErrorPresent", parseError != null)
        put("parseError", parseError?.let(::JsonPrimitive) ?: JsonNull)
        put("userInvocable", frontmatter?.userInvocable?.let(::JsonPrimitive) ?: JsonNull)
        put("disableModelInvocation", frontmatter?.disableModelInvocation?.let(::JsonPrimitive) ?: JsonNull)
        put("commandDispatch", frontmatter?.commandDispatch?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("commandTool", frontmatter?.commandTool?.let(::JsonPrimitive) ?: JsonNull)
        put("modelReady", isSkillCommandModelReady())
        put("invocable", isSkillCommandInvocable())
        put("readyForUse", requirements.isEmpty())
        put("setupStatus", requirements.toSkillSetupStatus())
        put("setupStepCount", requirements.size)
        put("readOnly", true)
        put("executesSetup", false)
        put("mutatesSkill", false)
        put("writesConfig", false)
        put("writesSecret", false)
        put("includeMarkdown", includeMarkdown)
        put("instructionsIncluded", false)
        put("instructionsOmitted", true)
        put("secretValuesIncluded", false)
        put("secretValuesOmitted", true)
        put("configValuesIncluded", false)
        put("configValuesOmitted", true)
        put("baseDirIncluded", false)
        put("rawFrontmatterIncluded", false)
        put("secretFieldCount", configuration.secretFields.size)
        put("configuredSecretFieldCount", configuration.secretFields.count { field -> field.configured })
        put("missingSecretFieldCount", configuration.secretFields.count { field -> !field.configured })
        put("configFieldCount", configuration.configFields.size)
        put("configuredConfigFieldCount", configuration.configFields.count { field -> field.value != null })
        put("missingConfigFieldCount", configuration.configFields.count { field -> field.value == null })
        put("recoveryMessage", configuration.recoveryMessage?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "secretFields",
            buildJsonArray {
                configuration.secretFields.forEach { field ->
                    add(
                        buildJsonObject {
                            put("envName", field.envName)
                            put("configured", field.configured)
                            put("valueIncluded", false)
                        },
                    )
                }
            },
        )
        put(
            "configFields",
            buildJsonArray {
                configuration.configFields.forEach { field ->
                    add(
                        buildJsonObject {
                            put("path", field.path)
                            put("configured", field.value != null)
                            put("valueIncluded", false)
                        },
                    )
                }
            },
        )
        put(
            "requirements",
            buildJsonArray {
                requirements.forEach { requirement ->
                    add(requirement.toSkillSetupRequirementPayload())
                }
            },
        )
        put(
            "suggestedTools",
            buildJsonArray {
                toSkillSetupSuggestedTools(requirements).forEach { toolName ->
                    add(JsonPrimitive(toolName))
                }
            },
        )
        put("setupMarkdown", setupMarkdown?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun List<SkillSetupReadinessEntry>.toSkillSetupMatrixPayload(
    skillCount: Int,
    candidateSkillCount: Int,
    limit: Int,
    includeDisabled: Boolean,
    includeRequirements: Boolean,
    includeMarkdown: Boolean,
    matrixMarkdown: String?,
): JsonObject {
    val statuses = map { entry -> entry.setupStatus }
    val omittedSkillCount = (candidateSkillCount - size).coerceAtLeast(0)
    return buildJsonObject {
        put("skillCount", skillCount)
        put("candidateSkillCount", candidateSkillCount)
        put("includedSkillCount", size)
        put("omittedSkillCount", omittedSkillCount)
        put("disabledSkillOmittedCount", if (includeDisabled) 0 else skillCount - candidateSkillCount)
        put("limit", limit)
        put("includeDisabled", includeDisabled)
        put("includeRequirements", includeRequirements)
        put("includeMarkdown", includeMarkdown)
        put("readySkillCount", count { entry -> entry.readyForUse })
        put("needsSetupSkillCount", count { entry -> !entry.readyForUse })
        put("disabledStatusSkillCount", statuses.count { status -> status == "DISABLED" })
        put("notEligibleSkillCount", statuses.count { status -> status == "NOT_ELIGIBLE" })
        put("needsConfigSkillCount", statuses.count { status -> status == "NEEDS_CONFIG" })
        put("needsSecretsSkillCount", statuses.count { status -> status == "NEEDS_SECRETS" })
        put(
            "needsConfigAndSecretsSkillCount",
            statuses.count { status -> status == "NEEDS_CONFIG_AND_SECRETS" },
        )
        put("readOnly", true)
        put("executesSetup", false)
        put("mutatesSkill", false)
        put("writesConfig", false)
        put("writesSecret", false)
        put("instructionsIncluded", false)
        put("instructionsOmitted", true)
        put("secretValuesIncluded", false)
        put("secretValuesOmitted", true)
        put("configValuesIncluded", false)
        put("configValuesOmitted", true)
        put("baseDirIncluded", false)
        put("rawFrontmatterIncluded", false)
        put(
            "setupStatusStats",
            buildJsonArray {
                statuses
                    .groupingBy { status -> status }
                    .eachCount()
                    .toList()
                    .sortedBy { (status, _) -> status }
                    .forEach { (status, count) ->
                        add(namedCountPayload(nameField = "setupStatus", name = status, countField = "skillCount", count = count))
                    }
            },
        )
        put(
            "readySkillIds",
            buildJsonArray {
                this@toSkillSetupMatrixPayload
                    .filter { entry -> entry.readyForUse }
                    .forEach { entry -> add(JsonPrimitive(entry.skill.id)) }
            },
        )
        put(
            "suggestedTools",
            buildJsonArray {
                this@toSkillSetupMatrixPayload
                    .flatMap { entry -> entry.skill.toSkillSetupSuggestedTools(entry.requirements) }
                    .distinct()
                    .forEach { toolName -> add(JsonPrimitive(toolName)) }
            },
        )
        put(
            "skills",
            buildJsonArray {
                this@toSkillSetupMatrixPayload.forEach { entry ->
                    add(entry.toSkillSetupMatrixSkillPayload(includeRequirements = includeRequirements))
                }
            },
        )
        put("matrixMarkdown", matrixMarkdown?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal fun SkillSetupReadinessEntry.toSkillSetupMatrixSkillPayload(includeRequirements: Boolean): JsonObject =
    buildJsonObject {
        put("skillId", skill.id)
        put("skillKey", skill.skillKey)
        put("name", skill.displayName)
        put("enabled", skill.enabled)
        put("sourceType", skill.sourceType.name)
        put("workspaceSessionId", skill.workspaceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("resolutionState", skill.resolutionState.name)
        put("shadowedBy", skill.shadowedBy?.let(::JsonPrimitive) ?: JsonNull)
        put("eligibilityStatus", skill.eligibility.status.name)
        put("hasFrontmatter", skill.frontmatter != null)
        put("parseErrorPresent", skill.parseError != null)
        put(
            "commandDispatch",
            skill.frontmatter
                ?.commandDispatch
                ?.name
                ?.let(::JsonPrimitive) ?: JsonNull,
        )
        put("commandTool", skill.frontmatter?.commandTool?.let(::JsonPrimitive) ?: JsonNull)
        put("modelReady", skill.isSkillCommandModelReady())
        put("invocable", skill.isSkillCommandInvocable())
        put("readyForUse", readyForUse)
        put("setupStatus", setupStatus)
        put("setupStepCount", requirements.size)
        put("secretFieldCount", configuration.secretFields.size)
        put("configuredSecretFieldCount", configuration.secretFields.count { field -> field.configured })
        put("missingSecretFieldCount", configuration.secretFields.count { field -> !field.configured })
        put("configFieldCount", configuration.configFields.size)
        put("configuredConfigFieldCount", configuration.configFields.count { field -> field.value != null })
        put("missingConfigFieldCount", configuration.configFields.count { field -> field.value == null })
        put("recoveryMessagePresent", configuration.recoveryMessage != null)
        put("instructionsIncluded", false)
        put("instructionsOmitted", true)
        put("secretValuesIncluded", false)
        put("configValuesIncluded", false)
        put("baseDirIncluded", false)
        put("rawFrontmatterIncluded", false)
        put(
            "suggestedTools",
            buildJsonArray {
                skill
                    .toSkillSetupSuggestedTools(requirements)
                    .forEach { toolName -> add(JsonPrimitive(toolName)) }
            },
        )
        put(
            "requirements",
            if (includeRequirements) {
                buildJsonArray {
                    requirements.forEach { requirement ->
                        add(requirement.toSkillSetupRequirementPayload())
                    }
                }
            } else {
                JsonNull
            },
        )
    }

internal fun List<SkillSetupReadinessEntry>.toSkillSetupMatrixMarkdown(
    skillCount: Int,
    candidateSkillCount: Int,
    limit: Int,
    includeDisabled: Boolean,
    includeRequirements: Boolean,
): String =
    buildString {
        appendLine("# Skill setup matrix")
        appendLine()
        appendLine("- Skills in inventory: $skillCount")
        appendLine("- Candidate skills after filters: $candidateSkillCount")
        appendLine("- Skills included: ${this@toSkillSetupMatrixMarkdown.size} of up to $limit")
        appendLine("- Disabled skills included: $includeDisabled")
        appendLine("- Requirement details included: $includeRequirements")
        appendLine("- Ready skills: ${this@toSkillSetupMatrixMarkdown.count { entry -> entry.readyForUse }}")
        appendLine("- Instructions included: false")
        appendLine("- Secret values included: false")
        appendLine("- Config values included: false")
        appendLine("- Base directories included: false")
        appendLine("- Read-only: true")
        appendLine("- Executes setup: false")
        appendLine()
        appendLine("## Skills")
        if (this@toSkillSetupMatrixMarkdown.isEmpty()) {
            appendLine("_No skills included._")
        } else {
            this@toSkillSetupMatrixMarkdown.forEach { entry ->
                append("- `")
                append(entry.skill.id.toHandoffLine())
                append("` name=`")
                append(entry.skill.displayName.toHandoffLine())
                append("` enabled=")
                append(entry.skill.enabled)
                append(" status=")
                append(entry.setupStatus)
                append(" requirements=")
                append(entry.requirements.size)
                appendLine()
                if (includeRequirements) {
                    entry.requirements.forEach { requirement ->
                        append("  - ")
                        append(requirement.code)
                        append(": ")
                        append(requirement.summary.toHandoffLine())
                        appendLine()
                    }
                }
            }
        }
    }

internal fun List<SkillSetupRequirement>.toSkillSetupStatus(): String =
    when {
        isEmpty() -> "READY"
        any { requirement -> requirement.code == "skill.disabled" } -> "DISABLED"
        any { requirement -> requirement.code.startsWith("skill.parse.") || requirement.code.startsWith("skill.eligibility.") } -> "NOT_ELIGIBLE"
        any { requirement -> requirement.code.startsWith("skill.secret.") } &&
            any { requirement -> requirement.code.startsWith("skill.config.") } -> "NEEDS_CONFIG_AND_SECRETS"
        any { requirement -> requirement.code.startsWith("skill.secret.") } -> "NEEDS_SECRETS"
        any { requirement -> requirement.code.startsWith("skill.config.") } -> "NEEDS_CONFIG"
        else -> "NEEDS_SETUP"
    }

internal fun SkillSetupRequirement.toSkillSetupRequirementPayload(): JsonObject =
    buildJsonObject {
        put("code", code)
        put("severity", severity)
        put("field", field?.let(::JsonPrimitive) ?: JsonNull)
        put("summary", summary)
        put("action", action)
        put("suggestedTool", suggestedTool)
    }

internal fun SkillSnapshot.toSkillSetupSuggestedTools(requirements: List<SkillSetupRequirement>): List<String> =
    buildList {
        add("skills.config.get")
        requirements
            .map { requirement -> requirement.suggestedTool }
            .filterTo(this) { toolName -> toolName.isNotBlank() }
        add("skills.get")
    }.distinct()

internal fun SkillSnapshot.toSkillSetupRequirements(configuration: SkillConfigurationSnapshot): List<SkillSetupRequirement> =
    buildList {
        fun addRequirement(
            code: String,
            severity: String,
            field: String?,
            summary: String,
            action: String,
            suggestedTool: String,
        ) {
            add(
                SkillSetupRequirement(
                    code = code,
                    severity = severity,
                    field = field,
                    summary = summary.toHandoffLine(),
                    action = action.toHandoffLine(),
                    suggestedTool = suggestedTool,
                ),
            )
        }

        if (!enabled) {
            addRequirement(
                code = "skill.disabled",
                severity = "Info",
                field = "enabled",
                summary = "Skill $displayName is disabled.",
                action = "Run skills.enable if this skill should be available to the agent.",
                suggestedTool = "skills.enable",
            )
        }
        if (frontmatter == null) {
            addRequirement(
                code = "skill.parse.frontmatter_missing",
                severity = "Error",
                field = "frontmatter",
                summary = "Skill $displayName has no parsed frontmatter.",
                action = "Inspect or reimport the SKILL.md definition.",
                suggestedTool = "skills.get",
            )
        }
        parseError?.let {
            addRequirement(
                code = "skill.parse.error",
                severity = "Error",
                field = "parseError",
                summary = "Skill $displayName has a parse error.",
                action = "Inspect the parse error and fix the SKILL.md file.",
                suggestedTool = "skills.get",
            )
        }
        if (resolutionState != SkillResolutionState.Effective) {
            addRequirement(
                code = "skill.resolution.shadowed",
                severity = "Warning",
                field = "resolutionState",
                summary = "Skill $displayName is shadowed by another skill.",
                action = "Inspect the skill inventory and disable or rename the shadowing skill if needed.",
                suggestedTool = "skills.list",
            )
        }
        if (eligibility.status != SkillEligibilityStatus.Eligible) {
            addRequirement(
                code = "skill.eligibility.${eligibility.status.name.lowercase()}",
                severity = "Error",
                field = "eligibilityStatus",
                summary = "Skill $displayName is not eligible: ${eligibility.status.name}.",
                action =
                    if (eligibility.status == SkillEligibilityStatus.MissingTool) {
                        "Inspect the command tool requirement before enabling this skill."
                    } else {
                        "Inspect skill metadata and eligibility reasons before using this skill."
                    },
                suggestedTool =
                    if (eligibility.status == SkillEligibilityStatus.MissingTool) {
                        "tools.resolve"
                    } else {
                        "skills.get"
                    },
            )
        }
        if (frontmatter?.commandDispatch == SkillCommandDispatch.Tool && frontmatter.commandTool.isNullOrBlank()) {
            addRequirement(
                code = "skill.command.tool_missing",
                severity = "Error",
                field = "commandTool",
                summary = "Skill $displayName dispatches to a tool but has no commandTool.",
                action = "Fix the skill frontmatter or reimport a valid skill definition.",
                suggestedTool = "skills.get",
            )
        }
        configuration.secretFields
            .filter { field -> !field.configured }
            .forEach { field ->
                addRequirement(
                    code = "skill.secret.missing",
                    severity = "Error",
                    field = field.envName,
                    summary = "Skill $displayName is missing required secret ${field.envName}.",
                    action = "Open the skill configuration UI and save the secret value, then rerun skills.config.get.",
                    suggestedTool = "skills.config.get",
                )
            }
        configuration.configFields
            .filter { field -> field.value == null }
            .forEach { field ->
                addRequirement(
                    code = "skill.config.missing",
                    severity = "Error",
                    field = field.path,
                    summary = "Skill $displayName is missing config ${field.path}.",
                    action = "Run skills.config.update with a non-secret value for this config path.",
                    suggestedTool = "skills.config.update",
                )
            }
        configuration.recoveryMessage?.let {
            addRequirement(
                code = "skill.config.recovery_notice",
                severity = "Warning",
                field = "recoveryMessage",
                summary = "Skill $displayName has a configuration recovery notice.",
                action = "Inspect skill configuration status and re-save any missing values if needed.",
                suggestedTool = "skills.config.get",
            )
        }
    }

internal fun SkillSnapshot.toSkillSetupMarkdown(
    configuration: SkillConfigurationSnapshot,
    requirements: List<SkillSetupRequirement>,
    requestedSkillId: String,
): String =
    buildString {
        appendLine("# Skill setup guide")
        appendLine()
        appendLine("- Skill: `$id` (${displayName.toHandoffLine()})")
        appendLine("- Requested skill: `${requestedSkillId.toHandoffLine()}`")
        appendLine("- Status: ${requirements.toSkillSetupStatus()}")
        appendLine("- Ready for use: ${requirements.isEmpty()}")
        appendLine("- Enabled: $enabled")
        appendLine("- Eligibility: ${eligibility.status.name}")
        appendLine("- Resolution: ${resolutionState.name}")
        appendLine("- Secret fields configured: ${configuration.secretFields.count { field -> field.configured }} / ${configuration.secretFields.size}")
        appendLine("- Config fields configured: ${configuration.configFields.count { field -> field.value != null }} / ${configuration.configFields.size}")
        appendLine("- Instructions included: false")
        appendLine("- Secret values included: false")
        appendLine("- Config values included: false")
        appendLine("- Base directory included: false")
        appendLine("- Read-only: true")
        appendLine("- Executes setup: false")
        appendLine()
        appendLine("## Requirements")
        if (requirements.isEmpty()) {
            appendLine("- None")
        } else {
            requirements.forEach { requirement ->
                append("- ")
                append(requirement.severity)
                append(" `")
                append(requirement.code)
                append("`: ")
                append(requirement.summary.toHandoffLine())
                append(" Action: ")
                append(requirement.action.toHandoffLine())
                append(" Tool: `")
                append(requirement.suggestedTool)
                appendLine("`")
            }
        }
    }

internal fun SkillSnapshot.toDefaultConfigurationSnapshot(): SkillConfigurationSnapshot =
    SkillConfigurationSnapshot(
        skillId = id,
        skillKey = skillKey,
        displayName = displayName,
        secretFields =
            secretStatuses.map { (envName, configured) ->
                SkillSecretField(
                    envName = envName,
                    configured = configured,
                )
            },
        configFields =
            configStatuses.map { (path, configured) ->
                SkillConfigField(
                    path = path,
                    value = if (configured) "" else null,
                )
            },
    )

internal fun SkillConfigurationSnapshot.withUpdatedConfigField(
    configPath: String,
    value: String?,
): SkillConfigurationSnapshot =
    copy(
        configFields =
            configFields.map { field ->
                if (field.path == configPath) {
                    field.copy(value = value)
                } else {
                    field
                }
            },
    )

internal fun SkillConfigurationSnapshot.withClearedSecretField(envName: String): SkillConfigurationSnapshot =
    copy(
        secretFields =
            secretFields.map { field ->
                if (field.envName == envName) {
                    field.copy(configured = false)
                } else {
                    field
                }
            },
    )

internal fun SkillConfigurationSnapshot.toSkillConfigurationPayload(): JsonObject =
    buildJsonObject {
        put("skillId", skillId?.let(::JsonPrimitive) ?: JsonNull)
        put("skillKey", skillKey)
        put("displayName", displayName?.let(::JsonPrimitive) ?: JsonNull)
        put("secretFieldCount", secretFields.size)
        put("configuredSecretFieldCount", secretFields.count { field -> field.configured })
        put("configFieldCount", configFields.size)
        put("configuredConfigFieldCount", configFields.count { field -> field.value != null })
        put("recoveryMessage", recoveryMessage?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "secretFields",
            buildJsonArray {
                secretFields.forEach { field ->
                    add(
                        buildJsonObject {
                            put("envName", field.envName)
                            put("configured", field.configured)
                        },
                    )
                }
            },
        )
        put(
            "configFields",
            buildJsonArray {
                configFields.forEach { field ->
                    val value = field.value
                    add(
                        buildJsonObject {
                            put("path", field.path)
                            put("configured", value != null)
                            put("value", value?.let(::JsonPrimitive) ?: JsonNull)
                        },
                    )
                }
            },
        )
    }

internal fun List<SkillSnapshot>.toSkillStatsPayload(): JsonObject {
    val totalSecretFieldCount = sumOf { skill -> skill.secretStatuses.size }
    val missingSecretFieldCount = sumOf { skill -> skill.secretStatuses.count { (_, configured) -> !configured } }
    val totalConfigFieldCount = sumOf { skill -> skill.configStatuses.size }
    val missingConfigFieldCount = sumOf { skill -> skill.configStatuses.count { (_, configured) -> !configured } }
    val modelReadySkillCount =
        count { skill ->
            val frontmatter = skill.frontmatter ?: return@count false
            skill.enabled &&
                skill.resolutionState == SkillResolutionState.Effective &&
                skill.eligibility.status == SkillEligibilityStatus.Eligible &&
                frontmatter.commandDispatch == SkillCommandDispatch.Model &&
                !frontmatter.disableModelInvocation
        }
    return buildJsonObject {
        put("skillCount", size)
        put("enabledSkillCount", count { skill -> skill.enabled })
        put("disabledSkillCount", count { skill -> !skill.enabled })
        put("eligibleSkillCount", count { skill -> skill.eligibility.status == SkillEligibilityStatus.Eligible })
        put("ineligibleSkillCount", count { skill -> skill.eligibility.status != SkillEligibilityStatus.Eligible })
        put("modelReadySkillCount", modelReadySkillCount)
        put("toolDispatchSkillCount", count { skill -> skill.frontmatter?.commandDispatch == SkillCommandDispatch.Tool })
        put("missingFrontmatterCount", count { skill -> skill.frontmatter == null })
        put("parseErrorCount", count { skill -> skill.parseError != null })
        put("skillsWithSecretFieldsCount", count { skill -> skill.secretStatuses.isNotEmpty() })
        put("totalSecretFieldCount", totalSecretFieldCount)
        put("missingSecretFieldCount", missingSecretFieldCount)
        put("skillsWithConfigFieldsCount", count { skill -> skill.configStatuses.isNotEmpty() })
        put("totalConfigFieldCount", totalConfigFieldCount)
        put("missingConfigFieldCount", missingConfigFieldCount)
        put(
            "sourceTypeStats",
            buildJsonArray {
                groupedCounts { skill -> skill.sourceType.name }
                    .forEach { (sourceType, count) ->
                        add(namedCountPayload(nameField = "sourceType", name = sourceType, countField = "skillCount", count = count))
                    }
            },
        )
        put(
            "eligibilityStats",
            buildJsonArray {
                groupedCounts { skill -> skill.eligibility.status.name }
                    .forEach { (status, count) ->
                        add(namedCountPayload(nameField = "eligibilityStatus", name = status, countField = "skillCount", count = count))
                    }
            },
        )
        put(
            "commandDispatchStats",
            buildJsonArray {
                groupedCounts { skill -> skill.frontmatter?.commandDispatch?.name ?: "MissingFrontmatter" }
                    .forEach { (dispatch, count) ->
                        add(namedCountPayload(nameField = "commandDispatch", name = dispatch, countField = "skillCount", count = count))
                    }
            },
        )
        put(
            "resolutionStateStats",
            buildJsonArray {
                groupedCounts { skill -> skill.resolutionState.name }
                    .forEach { (state, count) ->
                        add(namedCountPayload(nameField = "resolutionState", name = state, countField = "skillCount", count = count))
                    }
            },
        )
    }
}

internal fun List<SkillSnapshot>.groupedCounts(selector: (SkillSnapshot) -> String): List<Pair<String, Int>> =
    groupingBy(selector)
        .eachCount()
        .toList()
        .sortedBy { (name, _) -> name }

internal fun namedCountPayload(
    nameField: String,
    name: String,
    countField: String,
    count: Int,
): JsonObject =
    buildJsonObject {
        put(nameField, name)
        put(countField, count)
    }

internal fun SkillSnapshot.toSkillDetailPayload(includeInstructions: Boolean): JsonObject {
    val instructionsSnippet =
        if (instructionsMd.length <= SKILL_INSTRUCTIONS_MAX_CHARS) {
            instructionsMd
        } else {
            instructionsMd.take(SKILL_INSTRUCTIONS_MAX_CHARS)
        }
    return buildJsonObject {
        put("id", id)
        put("skillKey", skillKey)
        put("name", displayName)
        put("enabled", enabled)
        put("sourceType", sourceType.name)
        put("workspaceSessionId", workspaceSessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("baseDir", baseDir)
        put("resolutionState", resolutionState.name)
        put("shadowedBy", shadowedBy?.let(::JsonPrimitive) ?: JsonNull)
        put("eligibilityStatus", eligibility.status.name)
        put(
            "eligibilityReasons",
            buildJsonArray {
                eligibility.reasons.forEach { add(JsonPrimitive(it)) }
            },
        )
        put(
            "secretStatuses",
            buildJsonArray {
                secretStatuses.forEach { (envName, configured) ->
                    add(
                        buildJsonObject {
                            put("envName", envName)
                            put("configured", configured)
                        },
                    )
                }
            },
        )
        put(
            "configStatuses",
            buildJsonArray {
                configStatuses.forEach { (path, configured) ->
                    add(
                        buildJsonObject {
                            put("path", path)
                            put("configured", configured)
                        },
                    )
                }
            },
        )
        put("parseError", parseError?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "frontmatter",
            frontmatter?.let { metadata ->
                buildJsonObject {
                    put("name", metadata.name)
                    put("description", metadata.description)
                    put("homepage", metadata.homepage?.let(::JsonPrimitive) ?: JsonNull)
                    put("userInvocable", metadata.userInvocable)
                    put("disableModelInvocation", metadata.disableModelInvocation)
                    put("commandDispatch", metadata.commandDispatch.name)
                    put("commandTool", metadata.commandTool?.let(::JsonPrimitive) ?: JsonNull)
                    put("commandArgMode", metadata.commandArgMode)
                    put("metadata", metadata.metadata ?: JsonNull)
                    put(
                        "unknownFields",
                        buildJsonObject {
                            metadata.unknownFields.forEach { (field, value) ->
                                put(field, value)
                            }
                        },
                    )
                }
            } ?: JsonNull,
        )
        put("instructionsIncluded", includeInstructions)
        put("instructionsLength", instructionsMd.length)
        put("instructionsTruncated", instructionsSnippet.length < instructionsMd.length)
        put("instructionsMd", if (includeInstructions) JsonPrimitive(instructionsSnippet) else JsonNull)
    }
}
