package ai.androidclaw.runtime.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Clock

internal fun toolDiscoveryEntries(
    toolRegistryProvider: () -> ToolRegistry,
    clock: Clock,
): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.stats",
                    aliases = listOf("tool.stats"),
                    description = "Summarize typed native tool registry metadata without returning schemas.",
                ),
        ) { _, _ ->
            val tools = toolRegistryProvider().descriptors()
            ToolExecutionResult.success(
                summary = "Summarized ${tools.size} tool(s).",
                payload = tools.toToolStatsPayload(),
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.export",
                    aliases =
                        listOf(
                            "tool.export",
                            "tools.backup",
                            "tool.backup",
                            "tools.catalog.export",
                            "tool.catalog.export",
                        ),
                    description = "Export bounded typed native tool descriptors and capability metadata.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "toolName",
                                required = false,
                                description = "Optional canonical tool name or alias to include only one tool.",
                            ),
                            ToolArgumentSpec(
                                name = "namespace",
                                required = false,
                                description = "Optional canonical namespace prefix before the first dot.",
                            ),
                            ToolArgumentSpec(
                                name = "availableOnly",
                                description = "Set true to include only currently available tools. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "includeInputSchemas",
                                description = "Set true to include bounded JSON input schemas. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum tool descriptors to include. Defaults to 100, max 200.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit exportMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val tools = toolRegistryProvider().descriptors()
            val requestedToolName = arguments.optionalText("toolName") ?: arguments.optionalText("name")
            val namespaceFilter = arguments.optionalText("namespace")
            val availableOnly = arguments.optionalBoolean("availableOnly", defaultValue = false)
            val includeInputSchemas = arguments.optionalBoolean("includeInputSchemas", defaultValue = false)
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_EXPORT_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_EXPORT_MAX_LIMIT)
            val selectedTools =
                if (requestedToolName == null) {
                    tools
                } else {
                    listOf(
                        toolRegistryProvider().findDescriptor(requestedToolName)
                            ?: return@Entry ToolExecutionResult.failure(
                                summary = "Tool $requestedToolName was not found.",
                                errorCode = "TOOL_NOT_FOUND",
                                payload =
                                    buildJsonObject {
                                        put("errorCode", "TOOL_NOT_FOUND")
                                        put("toolName", requestedToolName)
                                    },
                            ),
                    )
                }
            val candidates =
                selectedTools.filter { tool ->
                    (namespaceFilter == null || tool.toolNamespace().equals(namespaceFilter, ignoreCase = true)) &&
                        (!availableOnly || tool.availability.status == ToolAvailabilityStatus.Available)
                }
            val includedTools = candidates.take(limit)
            val exportMarkdown =
                if (includeMarkdown) {
                    includedTools.toToolExportMarkdown(
                        totalToolCount = tools.size,
                        candidateToolCount = candidates.size,
                        requestedToolName = requestedToolName,
                        namespaceFilter = namespaceFilter,
                        availableOnly = availableOnly,
                        includeInputSchemas = includeInputSchemas,
                        limit = limit,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    if (requestedToolName == null) {
                        "Prepared tool export with ${includedTools.size} of ${candidates.size} candidate tool(s)."
                    } else {
                        "Prepared tool export for ${includedTools.size} matching tool(s)."
                    },
                payload =
                    buildJsonObject {
                        put("exportFormat", TOOL_EXPORT_FORMAT)
                        put("exportVersion", TOOL_EXPORT_VERSION)
                        put("generatedAtIso", clock.instant().toString())
                        put("toolCount", tools.size)
                        put("candidateToolCount", candidates.size)
                        put("includedToolCount", includedTools.size)
                        put("omittedToolCount", (candidates.size - includedTools.size).coerceAtLeast(0))
                        put("requestedToolName", requestedToolName?.let(::JsonPrimitive) ?: JsonNull)
                        put("namespace", namespaceFilter?.let(::JsonPrimitive) ?: JsonNull)
                        put("canonicalNamespace", candidates.firstOrNull()?.toolNamespace()?.let(::JsonPrimitive) ?: JsonNull)
                        put("availableOnly", availableOnly)
                        put("includeInputSchemas", includeInputSchemas)
                        put("inputSchemasIncluded", includeInputSchemas)
                        put("includeMarkdown", includeMarkdown)
                        put("limit", limit)
                        put("secretValuesIncluded", false)
                        put("executionResultsIncluded", false)
                        put("runtimeStateIncluded", false)
                        put("availabilityIncluded", true)
                        put("stats", tools.toToolStatsPayload())
                        put(
                            "tools",
                            buildJsonArray {
                                includedTools.forEach { tool ->
                                    add(tool.toToolExportPayload(includeInputSchema = includeInputSchemas))
                                }
                            },
                        )
                        put("exportMarkdown", exportMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.handoff",
                    aliases =
                        listOf(
                            "tool.handoff",
                            "tools.snapshot",
                            "tool.snapshot",
                        ),
                    description = "Return a compact tool registry handoff without input schemas.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "namespace",
                                required = false,
                                description = "Optional canonical namespace prefix before the first dot.",
                            ),
                            ToolArgumentSpec(
                                name = "availableOnly",
                                description = "Set true to include only currently available tools. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum tool entries to include. Defaults to 12.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit handoffMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val tools = toolRegistryProvider().descriptors()
            val namespaceFilter = arguments.optionalText("namespace") ?: arguments.optionalText("name")
            val availableOnly = arguments.optionalBoolean("availableOnly", defaultValue = false)
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_HANDOFF_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_HANDOFF_MAX_LIMIT)
            val candidates =
                tools.filter { tool ->
                    (namespaceFilter == null || tool.toolNamespace().equals(namespaceFilter, ignoreCase = true)) &&
                        (!availableOnly || tool.availability.status == ToolAvailabilityStatus.Available)
                }
            val includedTools = candidates.take(limit)
            val handoffMarkdown =
                if (includeMarkdown) {
                    includedTools.toToolHandoffMarkdown(
                        totalToolCount = tools.size,
                        candidateToolCount = candidates.size,
                        namespaceFilter = namespaceFilter,
                        availableOnly = availableOnly,
                        limit = limit,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    if (tools.isEmpty()) {
                        "Prepared empty tool handoff."
                    } else {
                        "Prepared tool handoff with ${includedTools.size} of ${candidates.size} candidate tool(s)."
                    },
                payload =
                    buildJsonObject {
                        put("toolCount", tools.size)
                        put("candidateToolCount", candidates.size)
                        put("includedToolCount", includedTools.size)
                        put("omittedToolCount", (candidates.size - includedTools.size).coerceAtLeast(0))
                        put("namespace", namespaceFilter?.let(::JsonPrimitive) ?: JsonNull)
                        put("canonicalNamespace", candidates.firstOrNull()?.toolNamespace()?.let(::JsonPrimitive) ?: JsonNull)
                        put("availableOnly", availableOnly)
                        put("limit", limit)
                        put("includeMarkdown", includeMarkdown)
                        put("stats", tools.toToolStatsPayload())
                        put(
                            "tools",
                            buildJsonArray {
                                includedTools.forEach { tool ->
                                    add(tool.toToolHandoffPayload())
                                }
                            },
                        )
                        put("handoffMarkdown", handoffMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.doctor",
                    aliases =
                        listOf(
                            "tool.doctor",
                            "tools.health",
                            "tool.health",
                            "tools.diagnostics",
                            "tool.diagnostics",
                        ),
                    description = "Return actionable typed-tool diagnostics without input schemas.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "namespace",
                                required = false,
                                description = "Optional canonical namespace prefix before the first dot.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum diagnostic issues to include. Defaults to 20.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit doctorMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val tools = toolRegistryProvider().descriptors()
            val namespaceFilter = arguments.optionalText("namespace") ?: arguments.optionalText("name")
            val candidates =
                tools.filter { tool ->
                    namespaceFilter == null || tool.toolNamespace().equals(namespaceFilter, ignoreCase = true)
                }
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_DOCTOR_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_DOCTOR_MAX_LIMIT)
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val issues = candidates.toToolDoctorIssues(namespaceFilter = namespaceFilter)
            val includedIssues = issues.take(limit)
            val status = issues.toToolDoctorStatus()
            val includedChecks = candidates.take(TOOL_DOCTOR_CHECK_MAX_LIMIT)
            val doctorMarkdown =
                if (includeMarkdown) {
                    includedIssues.toToolDoctorMarkdown(
                        status = status,
                        totalToolCount = tools.size,
                        candidateToolCount = candidates.size,
                        issueCount = issues.size,
                        namespaceFilter = namespaceFilter,
                        limit = limit,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    when {
                        issues.isEmpty() ->
                            "Tool doctor found no issues across ${candidates.size} candidate tool(s)."
                        includedIssues.size == issues.size ->
                            "Tool doctor found ${issues.size} issue(s) across ${candidates.size} candidate tool(s)."
                        else ->
                            "Tool doctor found ${issues.size} issue(s) and included ${includedIssues.size}."
                    },
                payload =
                    buildJsonObject {
                        put("status", status)
                        put("toolCount", tools.size)
                        put("candidateToolCount", candidates.size)
                        put("toolCheckCount", includedChecks.size)
                        put("toolChecksOmitted", (candidates.size - includedChecks.size).coerceAtLeast(0))
                        put("namespace", namespaceFilter?.let(::JsonPrimitive) ?: JsonNull)
                        put("canonicalNamespace", candidates.firstOrNull()?.toolNamespace()?.let(::JsonPrimitive) ?: JsonNull)
                        put("inputSchemaIncluded", false)
                        put("issueCount", issues.size)
                        put("includedIssueCount", includedIssues.size)
                        put("omittedIssueCount", (issues.size - includedIssues.size).coerceAtLeast(0))
                        put("errorCount", issues.count { issue -> issue.severity == "Error" })
                        put("warningCount", issues.count { issue -> issue.severity == "Warning" })
                        put("limit", limit)
                        put("includeMarkdown", includeMarkdown)
                        put("stats", tools.toToolStatsPayload())
                        put(
                            "toolChecks",
                            buildJsonArray {
                                includedChecks.forEach { tool ->
                                    add(tool.toToolDoctorCheckPayload())
                                }
                            },
                        )
                        put(
                            "issues",
                            buildJsonArray {
                                includedIssues.forEach { issue ->
                                    add(issue.toToolDoctorPayload())
                                }
                            },
                        )
                        put("doctorMarkdown", doctorMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.list",
                    aliases = listOf("tool.list"),
                    description = "List typed native tools with current availability and argument metadata.",
                ),
        ) { _, _ ->
            val tools = toolRegistryProvider().descriptors()
            ToolExecutionResult.success(
                summary = "Found ${tools.size} tool(s).",
                payload =
                    buildJsonObject {
                        put("toolCount", tools.size)
                        put(
                            "tools",
                            buildJsonArray {
                                tools.forEach { tool ->
                                    add(tool.toToolDescriptorPayload(includeInputSchema = false))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.get",
                    aliases = listOf("tool.get"),
                    description = "Return one typed native tool descriptor by canonical name or alias.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "toolName",
                                required = false,
                                description = "Canonical tool name or alias.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val requestedToolName =
                arguments.optionalText("toolName")
                    ?: arguments.optionalText("name")
                    ?: return@Entry invalidToolDiscoveryArguments(
                        toolName = "tools.get",
                        summary = "tools.get requires a non-empty toolName.",
                        field = "toolName",
                    )
            val tool =
                toolRegistryProvider().findDescriptor(requestedToolName)
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Tool $requestedToolName was not found.",
                        errorCode = "TOOL_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TOOL_NOT_FOUND")
                                put("toolName", requestedToolName)
                            },
                    )
            ToolExecutionResult.success(
                summary = "Loaded tool ${tool.name}.",
                payload =
                    buildJsonObject {
                        put("tool", tool.toToolDescriptorPayload(includeInputSchema = true))
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.resolve",
                    aliases = listOf("tool.resolve", "tools.alias", "tool.alias"),
                    description = "Resolve a requested tool name or alias to its canonical descriptor.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "toolName",
                                required = false,
                                description = "Canonical tool name or alias to resolve.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val requestedToolName =
                arguments.optionalText("toolName")
                    ?: arguments.optionalText("name")
                    ?: return@Entry invalidToolDiscoveryArguments(
                        toolName = "tools.resolve",
                        summary = "tools.resolve requires a non-empty toolName.",
                        field = "toolName",
                    )
            val tool =
                toolRegistryProvider().findDescriptor(requestedToolName)
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Tool $requestedToolName was not found.",
                        errorCode = "TOOL_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TOOL_NOT_FOUND")
                                put("toolName", requestedToolName)
                            },
                    )
            ToolExecutionResult.success(
                summary =
                    if (tool.name == requestedToolName) {
                        "Resolved canonical tool ${tool.name}."
                    } else {
                        "Resolved alias $requestedToolName to ${tool.name}."
                    },
                payload =
                    buildJsonObject {
                        put("requestedName", requestedToolName)
                        put("canonicalName", tool.name)
                        put("isAlias", requestedToolName != tool.name)
                        put("aliasCount", tool.aliases.size)
                        put(
                            "matchedAlias",
                            if (requestedToolName != tool.name && requestedToolName in tool.aliases) {
                                JsonPrimitive(requestedToolName)
                            } else {
                                JsonNull
                            },
                        )
                        put("availabilityStatus", tool.availability.status.name)
                        put("availabilityReason", tool.availability.reason?.let(::JsonPrimitive) ?: JsonNull)
                        put("description", tool.description)
                        put(
                            "aliases",
                            buildJsonArray {
                                tool.aliases.forEach { alias -> add(JsonPrimitive(alias)) }
                            },
                        )
                        put(
                            "arguments",
                            buildJsonArray {
                                tool.arguments.forEach { argument ->
                                    add(
                                        buildJsonObject {
                                            put("name", argument.name)
                                            put("required", argument.required)
                                            put("description", argument.description)
                                        },
                                    )
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.example",
                    aliases =
                        listOf(
                            "tool.example",
                            "tools.invoke.example",
                            "tool.invoke.example",
                            "tools.sample",
                            "tool.sample",
                        ),
                    description = "Return a safe example argument object for a typed tool without executing it.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "toolName",
                                required = false,
                                description = "Canonical tool name or alias to generate an example for.",
                            ),
                            ToolArgumentSpec(
                                name = "includeOptional",
                                description = "Set false to include only required arguments. Defaults to true.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit exampleMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val requestedToolName =
                arguments.optionalText("toolName")
                    ?: arguments.optionalText("name")
                    ?: return@Entry invalidToolDiscoveryArguments(
                        toolName = "tools.example",
                        summary = "tools.example requires a non-empty toolName.",
                        field = "toolName",
                    )
            val tool =
                toolRegistryProvider().findDescriptor(requestedToolName)
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Tool $requestedToolName was not found.",
                        errorCode = "TOOL_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TOOL_NOT_FOUND")
                                put("toolName", requestedToolName)
                            },
                    )
            val includeOptional = arguments.optionalBoolean("includeOptional", defaultValue = true)
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val exampleArguments =
                tool.toToolExampleArgumentsPayload(includeOptional = includeOptional)
            val exampleMarkdown =
                if (includeMarkdown) {
                    tool.toToolExampleMarkdown(
                        requestedToolName = requestedToolName,
                        includeOptional = includeOptional,
                        exampleArguments = exampleArguments,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary = "Prepared example arguments for ${tool.name} without executing it.",
                payload =
                    buildJsonObject {
                        put("requestedName", requestedToolName)
                        put("canonicalName", tool.name)
                        put("isAlias", requestedToolName != tool.name)
                        put("availabilityStatus", tool.availability.status.name)
                        put("availabilityReason", tool.availability.reason?.let(::JsonPrimitive) ?: JsonNull)
                        put("foregroundRequired", tool.foregroundRequired)
                        put("includeOptional", includeOptional)
                        put("includeMarkdown", includeMarkdown)
                        put("exampleOnly", true)
                        put("executesTool", false)
                        put("secretValuesIncluded", false)
                        put("componentPayloadsIncluded", false)
                        put("inputSchemaIncluded", false)
                        put("argumentCount", tool.arguments.size)
                        put("requiredArgumentCount", tool.arguments.count { argument -> argument.required })
                        put("optionalArgumentCount", tool.arguments.count { argument -> !argument.required })
                        put("includedArgumentCount", exampleArguments.size)
                        put("exampleArguments", exampleArguments)
                        put(
                            "arguments",
                            buildJsonArray {
                                tool.arguments
                                    .filter { argument -> includeOptional || argument.required }
                                    .forEach { argument ->
                                        add(argument.toToolExampleArgumentPayload(targetToolName = tool.name))
                                    }
                            },
                        )
                        put(
                            "requiredPermissions",
                            buildJsonArray {
                                tool.requiredPermissions.forEach { permission ->
                                    add(permission.toToolPermissionPayload())
                                }
                            },
                        )
                        put("exampleMarkdown", exampleMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.validate",
                    aliases =
                        listOf(
                            "tool.validate",
                            "tools.check",
                            "tool.check",
                            "tools.dry_run",
                            "tool.dry_run",
                        ),
                    description = "Dry-run a tool invocation by validating target, arguments, and availability without executing it.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "toolName",
                                required = false,
                                description = "Canonical tool name or alias to validate.",
                            ),
                            ToolArgumentSpec(
                                name = "arguments",
                                description = "JSON object containing candidate arguments for the target tool.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val requestedToolName =
                arguments.optionalText("toolName")
                    ?: arguments.optionalText("name")
                    ?: return@Entry invalidToolDiscoveryArguments(
                        toolName = "tools.validate",
                        summary = "tools.validate requires a non-empty toolName.",
                        field = "toolName",
                    )
            val candidateArguments =
                when (val nestedArguments = arguments["arguments"]) {
                    null ->
                        buildJsonObject {
                            arguments.forEach { (field, value) ->
                                if (field !in TOOL_VALIDATE_RESERVED_ARGUMENT_FIELDS) {
                                    put(field, value)
                                }
                            }
                        }
                    is JsonObject -> nestedArguments
                    else ->
                        return@Entry invalidToolDiscoveryArguments(
                            toolName = "tools.validate",
                            summary = "tools.validate requires arguments to be a JSON object when provided.",
                            field = "arguments",
                        )
                }
            val tool =
                toolRegistryProvider().findDescriptor(requestedToolName)
                    ?: return@Entry ToolExecutionResult.failure(
                        summary = "Tool $requestedToolName was not found.",
                        errorCode = "TOOL_NOT_FOUND",
                        payload =
                            buildJsonObject {
                                put("errorCode", "TOOL_NOT_FOUND")
                                put("toolName", requestedToolName)
                            },
                    )
            val declaredArguments = tool.arguments.map { argument -> argument.name }.toSet()
            val providedArguments = candidateArguments.keys.sorted()
            val missingRequiredArguments =
                tool.arguments
                    .filter { argument -> argument.required && !candidateArguments.hasProvidedToolArgument(argument.name) }
                    .map { argument -> argument.name }
            val unknownArguments =
                providedArguments.filterNot { argumentName -> argumentName in declaredArguments }
            val validArguments = missingRequiredArguments.isEmpty()
            val availableNow = tool.availability.status == ToolAvailabilityStatus.Available
            val readyToExecute = validArguments && availableNow
            ToolExecutionResult.success(
                summary =
                    when {
                        readyToExecute -> "Tool ${tool.name} would pass registry validation and availability checks."
                        !validArguments -> "Tool ${tool.name} is missing required arguments."
                        else -> "Tool ${tool.name} is not currently available."
                    },
                payload =
                    buildJsonObject {
                        put("requestedName", requestedToolName)
                        put("canonicalName", tool.name)
                        put("isAlias", requestedToolName != tool.name)
                        put("validArguments", validArguments)
                        put("availableNow", availableNow)
                        put("readyToExecute", readyToExecute)
                        put("wouldStartExecution", readyToExecute)
                        put("semanticValidationIncluded", false)
                        put("availabilityStatus", tool.availability.status.name)
                        put("availabilityReason", tool.availability.reason?.let(::JsonPrimitive) ?: JsonNull)
                        put("declaredArgumentCount", tool.arguments.size)
                        put("requiredArgumentCount", tool.arguments.count { argument -> argument.required })
                        put("providedArgumentCount", providedArguments.size)
                        put("providedArguments", providedArguments.toToolStringArrayPayload())
                        put("missingRequiredArguments", missingRequiredArguments.toToolStringArrayPayload())
                        put("unknownArguments", unknownArguments.toToolStringArrayPayload())
                        put(
                            "argumentRequirements",
                            buildJsonArray {
                                tool.arguments.forEach { argument ->
                                    add(
                                        buildJsonObject {
                                            put("name", argument.name)
                                            put("required", argument.required)
                                            put("provided", argument.name in candidateArguments)
                                            put("description", argument.description)
                                        },
                                    )
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.arguments",
                    aliases =
                        listOf(
                            "tool.arguments",
                            "tools.by_argument",
                            "tool.by_argument",
                            "tools.arg",
                            "tool.arg",
                        ),
                    description = "Summarize argument names or list tools that declare one argument.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "argumentName",
                                required = false,
                                description = "Optional argument name to filter by. Alias: name.",
                            ),
                            ToolArgumentSpec(
                                name = "requiredOnly",
                                description = "Set true to include only tools where the matched argument is required.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum result count. Defaults to 50, max 100.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val tools = toolRegistryProvider().descriptors()
            val requestedArgumentName = arguments.optionalText("argumentName") ?: arguments.optionalText("name")
            val requiredOnly = arguments.optionalBoolean("requiredOnly")
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_ARGUMENTS_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_ARGUMENTS_MAX_LIMIT)
            if (requestedArgumentName == null) {
                return@Entry ToolExecutionResult.success(
                    summary = "Summarized declared arguments across ${tools.size} tool(s).",
                    payload =
                        tools.toToolArgumentStatsPayload(
                            limit = limit,
                            requiredOnly = requiredOnly,
                        ),
                )
            }

            val matchingTools =
                tools.mapNotNull { tool ->
                    val matchingArguments =
                        tool.arguments.filter { argument ->
                            argument.name.equals(requestedArgumentName, ignoreCase = true) &&
                                (!requiredOnly || argument.required)
                        }
                    if (matchingArguments.isEmpty()) {
                        null
                    } else {
                        tool to matchingArguments
                    }
                }
            val limitedMatches = matchingTools.take(limit)
            ToolExecutionResult.success(
                summary =
                    if (matchingTools.isEmpty()) {
                        "No tools declare argument $requestedArgumentName."
                    } else {
                        "Found ${limitedMatches.size} tool(s) declaring argument $requestedArgumentName."
                    },
                payload =
                    buildJsonObject {
                        put("argumentName", requestedArgumentName)
                        put("requiredOnly", requiredOnly)
                        put("limit", limit)
                        put("totalMatchCount", matchingTools.size)
                        put("resultCount", limitedMatches.size)
                        if (matchingTools.size > limitedMatches.size) {
                            put("omittedCount", matchingTools.size - limitedMatches.size)
                        }
                        put(
                            "tools",
                            buildJsonArray {
                                limitedMatches.forEach { (tool, matchingArguments) ->
                                    add(tool.toToolArgumentMatchPayload(matchingArguments))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.availability",
                    aliases =
                        listOf(
                            "tool.availability",
                            "tools.status",
                            "tool.status",
                            "tools.readiness",
                            "tool.readiness",
                        ),
                    description = "Summarize tool availability or list tools by one availability status.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "status",
                                required = false,
                                description =
                                    "Optional availability status: available, unavailable, permission_required, " +
                                        "foreground_required, or disabled_by_config.",
                            ),
                            ToolArgumentSpec(
                                name = "foregroundRequiredOnly",
                                description = "Set true to include only foreground-required tools.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum result count. Defaults to 50, max 100.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val tools = toolRegistryProvider().descriptors()
            val requestedStatusText = arguments.optionalText("status")
            val requestedStatus =
                requestedStatusText?.toToolAvailabilityStatusOrNull()
                    ?: if (requestedStatusText == null) {
                        null
                    } else {
                        return@Entry invalidToolDiscoveryArguments(
                            toolName = "tools.availability",
                            summary = "tools.availability received an unknown availability status.",
                            field = "status",
                        )
                    }
            val foregroundRequiredOnly = arguments.optionalBoolean("foregroundRequiredOnly")
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_AVAILABILITY_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_AVAILABILITY_MAX_LIMIT)

            if (requestedStatus == null) {
                return@Entry ToolExecutionResult.success(
                    summary = "Summarized availability across ${tools.size} tool(s).",
                    payload =
                        tools.toToolAvailabilityStatsPayload(
                            limit = limit,
                            foregroundRequiredOnly = foregroundRequiredOnly,
                        ),
                )
            }

            val matchingTools =
                tools.filter { tool ->
                    tool.availability.status == requestedStatus &&
                        (!foregroundRequiredOnly || tool.foregroundRequired)
                }
            val limitedMatches = matchingTools.take(limit)
            ToolExecutionResult.success(
                summary =
                    if (matchingTools.isEmpty()) {
                        "No tools currently have availability status ${requestedStatus.name}."
                    } else {
                        "Found ${limitedMatches.size} tool(s) with availability status ${requestedStatus.name}."
                    },
                payload =
                    buildJsonObject {
                        put("availabilityStatus", requestedStatus.name)
                        put("foregroundRequiredOnly", foregroundRequiredOnly)
                        put("limit", limit)
                        put("totalMatchCount", matchingTools.size)
                        put("resultCount", limitedMatches.size)
                        if (matchingTools.size > limitedMatches.size) {
                            put("omittedCount", matchingTools.size - limitedMatches.size)
                        }
                        put(
                            "tools",
                            buildJsonArray {
                                limitedMatches.forEach { tool ->
                                    add(tool.toToolAvailabilityMatchPayload())
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.permissions",
                    aliases =
                        listOf(
                            "tool.permissions",
                            "tools.permission",
                            "tool.permission",
                            "tools.by_permission",
                            "tool.by_permission",
                        ),
                    description = "Summarize Android permission requirements or list tools requiring one permission.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "permission",
                                required = false,
                                description = "Optional permission name, suffix, or display name to filter by. Alias: name.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum result count. Defaults to 50, max 100.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val tools = toolRegistryProvider().descriptors()
            val requestedPermission = arguments.optionalText("permission") ?: arguments.optionalText("name")
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_PERMISSIONS_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_PERMISSIONS_MAX_LIMIT)
            if (requestedPermission == null) {
                return@Entry ToolExecutionResult.success(
                    summary = "Summarized permission requirements across ${tools.size} tool(s).",
                    payload = tools.toToolPermissionDiscoveryPayload(limit),
                )
            }

            val matchingTools =
                tools.mapNotNull { tool ->
                    val matchingPermissions =
                        tool.requiredPermissions.filter { permission ->
                            permission.matchesPermissionQuery(requestedPermission)
                        }
                    if (matchingPermissions.isEmpty()) {
                        null
                    } else {
                        tool to matchingPermissions
                    }
                }
            val limitedMatches = matchingTools.take(limit)
            ToolExecutionResult.success(
                summary =
                    if (matchingTools.isEmpty()) {
                        "No tools require permission $requestedPermission."
                    } else {
                        "Found ${limitedMatches.size} tool(s) requiring permission $requestedPermission."
                    },
                payload =
                    buildJsonObject {
                        put("permission", requestedPermission)
                        put("limit", limit)
                        put("totalMatchCount", matchingTools.size)
                        put("resultCount", limitedMatches.size)
                        if (matchingTools.size > limitedMatches.size) {
                            put("omittedCount", matchingTools.size - limitedMatches.size)
                        }
                        put(
                            "tools",
                            buildJsonArray {
                                limitedMatches.forEach { (tool, matchingPermissions) ->
                                    add(tool.toToolPermissionMatchPayload(matchingPermissions))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.namespaces",
                    aliases =
                        listOf(
                            "tool.namespaces",
                            "tools.namespace",
                            "tool.namespace",
                            "tools.groups",
                            "tool.groups",
                        ),
                    description = "Summarize canonical tool namespaces or list tools in one namespace.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "namespace",
                                required = false,
                                description = "Optional canonical namespace prefix before the first dot. Alias: name.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum result count. Defaults to 50, max 100.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val tools = toolRegistryProvider().descriptors()
            val requestedNamespace = arguments.optionalText("namespace") ?: arguments.optionalText("name")
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_NAMESPACES_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_NAMESPACES_MAX_LIMIT)
            if (requestedNamespace == null) {
                return@Entry ToolExecutionResult.success(
                    summary = "Summarized ${tools.size} tool(s) by canonical namespace.",
                    payload = tools.toToolNamespaceDiscoveryPayload(limit),
                )
            }

            val matchingTools =
                tools.filter { tool ->
                    tool.toolNamespace().equals(requestedNamespace, ignoreCase = true)
                }
            val limitedMatches = matchingTools.take(limit)
            ToolExecutionResult.success(
                summary =
                    if (matchingTools.isEmpty()) {
                        "No tools found in namespace $requestedNamespace."
                    } else {
                        "Found ${limitedMatches.size} tool(s) in namespace ${matchingTools.first().toolNamespace()}."
                    },
                payload =
                    buildJsonObject {
                        put("namespace", requestedNamespace)
                        put("canonicalNamespace", matchingTools.firstOrNull()?.toolNamespace()?.let(::JsonPrimitive) ?: JsonNull)
                        put("limit", limit)
                        put("totalMatchCount", matchingTools.size)
                        put("resultCount", limitedMatches.size)
                        if (matchingTools.size > limitedMatches.size) {
                            put("omittedCount", matchingTools.size - limitedMatches.size)
                        }
                        put("availabilityStats", matchingTools.toToolAvailabilityStatsByStatusPayload())
                        put(
                            "tools",
                            buildJsonArray {
                                limitedMatches.forEach { tool ->
                                    add(tool.toToolNamespaceMatchPayload())
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "tools.search",
                    aliases = listOf("tool.search"),
                    description = "Search typed native tools by name, alias, description, permission, or argument metadata.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "query",
                                required = true,
                                description = "Tool text to search for.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum result count. Defaults to 20.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val query =
                arguments.optionalText("query")
                    ?: return@Entry invalidToolDiscoveryArguments(
                        toolName = "tools.search",
                        summary = "tools.search requires a non-empty query.",
                        field = "query",
                    )
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = TOOL_SEARCH_DEFAULT_LIMIT,
                    ).coerceIn(0, TOOL_SEARCH_MAX_LIMIT)
            val matches =
                toolRegistryProvider()
                    .descriptors()
                    .filter { tool -> tool.matchesToolQuery(query) }
                    .take(limit)
            ToolExecutionResult.success(
                summary =
                    if (matches.isEmpty()) {
                        "No tools matched \"$query\"."
                    } else {
                        "Found ${matches.size} tool(s) matching \"$query\"."
                    },
                payload =
                    buildJsonObject {
                        put("query", query)
                        put("resultCount", matches.size)
                        put(
                            "tools",
                            buildJsonArray {
                                matches.forEach { tool ->
                                    add(tool.toToolDescriptorPayload(includeInputSchema = false))
                                }
                            },
                        )
                    },
            )
        },
    )

// These handlers are the typed automation contract for v5. They intentionally mirror the
// repository's real schedule model instead of inventing a second scheduler abstraction.

private fun invalidToolDiscoveryArguments(
    toolName: String,
    summary: String,
    field: String,
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

private fun JsonObject.hasProvidedToolArgument(argumentName: String): Boolean {
    val value = this[argumentName] ?: return false
    return value !is JsonPrimitive || value.content.isNotBlank()
}

internal fun List<String>.toToolStringArrayPayload(): JsonArray =
    buildJsonArray {
        forEach { value ->
            add(JsonPrimitive(value))
        }
    }

internal fun List<ToolDescriptor>.toToolStatsPayload(): JsonObject =
    buildJsonObject {
        put("toolCount", size)
        put("totalToolCount", size)
        put("availableToolCount", count { tool -> tool.availability.status == ToolAvailabilityStatus.Available })
        put("foregroundRequiredToolCount", count { tool -> tool.foregroundRequired })
        put("toolsWithRequiredPermissionsCount", count { tool -> tool.requiredPermissions.isNotEmpty() })
        put("totalRequiredPermissionCount", sumOf { tool -> tool.requiredPermissions.size })
        put("toolsWithAliasesCount", count { tool -> tool.aliases.isNotEmpty() })
        put("aliasCount", sumOf { tool -> tool.aliases.size })
        put("toolsWithArgumentsCount", count { tool -> tool.arguments.isNotEmpty() })
        put("totalArgumentCount", sumOf { tool -> tool.arguments.size })
        put("requiredArgumentCount", sumOf { tool -> tool.arguments.count { argument -> argument.required } })
        put("inputSchemaIncluded", false)
        put("availabilityStats", toToolAvailabilityStatsPayload())
        put("permissionStats", toToolPermissionStatsPayload())
    }

private fun List<ToolDescriptor>.toToolAvailabilityStatsPayload(): JsonArray {
    val countsByStatus = groupingBy { tool -> tool.availability.status }.eachCount()
    return buildJsonArray {
        ToolAvailabilityStatus.entries.forEach { status ->
            countsByStatus[status]?.let { count ->
                add(
                    buildJsonObject {
                        put("status", status.name)
                        put("toolCount", count)
                    },
                )
            }
        }
    }
}

private fun List<ToolDescriptor>.toToolPermissionStatsPayload(): JsonArray {
    val stats =
        flatMap { tool ->
            tool.requiredPermissions.map { permission ->
                tool.name to permission
            }
        }.groupBy { (_, permission) ->
            permission.permission to permission.displayName
        }.toList()
            .sortedWith(
                compareBy(
                    { (permissionKey, _) -> permissionKey.first },
                    { (permissionKey, _) -> permissionKey.second },
                ),
            )
    return buildJsonArray {
        stats.forEach { (permissionKey, entries) ->
            add(
                buildJsonObject {
                    put("permission", permissionKey.first)
                    put("displayName", permissionKey.second)
                    put("toolCount", entries.map { (toolName, _) -> toolName }.distinct().size)
                    put("requirementCount", entries.size)
                },
            )
        }
    }
}

private fun List<ToolDescriptor>.toToolArgumentStatsPayload(
    limit: Int,
    requiredOnly: Boolean,
): JsonObject {
    val stats =
        flatMap { tool ->
            tool.arguments
                .filter { argument -> !requiredOnly || argument.required }
                .map { argument -> tool to argument }
        }.groupBy { (_, argument) -> argument.name }
            .toList()
            .sortedWith(
                compareByDescending<Pair<String, List<Pair<ToolDescriptor, ToolArgumentSpec>>>> { (_, entries) ->
                    entries.map { (tool, _) -> tool.name }.distinct().size
                }.thenBy { (argumentName, _) -> argumentName },
            )
    val limitedStats = stats.take(limit)
    return buildJsonObject {
        put("argumentName", JsonNull)
        put("requiredOnly", requiredOnly)
        put("limit", limit)
        put("uniqueArgumentCount", stats.size)
        put("resultCount", limitedStats.size)
        if (stats.size > limitedStats.size) {
            put("omittedCount", stats.size - limitedStats.size)
        }
        put(
            "arguments",
            buildJsonArray {
                limitedStats.forEach { (argumentName, entries) ->
                    val toolNames = entries.map { (tool, _) -> tool.name }.distinct().sorted()
                    val requiredCount = entries.count { (_, argument) -> argument.required }
                    val optionalCount = entries.size - requiredCount
                    add(
                        buildJsonObject {
                            put("name", argumentName)
                            put("toolCount", toolNames.size)
                            put("requiredCount", requiredCount)
                            put("optionalCount", optionalCount)
                            put(
                                "sampleTools",
                                buildJsonArray {
                                    toolNames.take(5).forEach { toolName ->
                                        add(JsonPrimitive(toolName))
                                    }
                                },
                            )
                            if (toolNames.size > 5) {
                                put("sampleToolsOmitted", toolNames.size - 5)
                            }
                        },
                    )
                }
            },
        )
    }
}

private fun ToolDescriptor.matchesToolQuery(query: String): Boolean {
    val normalizedQuery = query.lowercase()
    val values =
        buildList {
            add(name)
            add(description)
            addAll(aliases)
            add(availability.status.name)
            availability.reason?.let(::add)
            requiredPermissions.forEach { permission ->
                add(permission.permission)
                add(permission.displayName)
            }
            arguments.forEach { argument ->
                add(argument.name)
                add(argument.description)
            }
        }
    return values.any { value -> value.lowercase().contains(normalizedQuery) }
}

private fun ToolDescriptor.toToolArgumentMatchPayload(matchingArguments: List<ToolArgumentSpec>): JsonObject =
    buildJsonObject {
        put("name", name)
        put("description", description)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("foregroundRequired", foregroundRequired)
        put("argumentCount", arguments.size)
        put("requiredArgumentCount", arguments.count { argument -> argument.required })
        put(
            "aliases",
            buildJsonArray {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias))
                }
            },
        )
        put(
            "matchingArguments",
            buildJsonArray {
                matchingArguments.forEach { argument ->
                    add(argument.toToolArgumentPayload())
                }
            },
        )
    }

private fun ToolArgumentSpec.toToolArgumentPayload(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("required", required)
        put("description", description)
        put("type", type.name)
        put("validate", validate)
        put("sensitive", sensitive)
        if (enumValues.isNotEmpty()) {
            put("enumValues", enumValues.toToolStringArrayPayload())
        }
    }

private fun ToolDescriptor.toToolExampleArgumentsPayload(includeOptional: Boolean): JsonObject =
    buildJsonObject {
        arguments
            .filter { argument -> includeOptional || argument.required }
            .forEach { argument ->
                put(argument.name, argument.toToolExampleValue(targetToolName = name))
            }
    }

private fun ToolArgumentSpec.toToolExampleArgumentPayload(targetToolName: String): JsonObject =
    buildJsonObject {
        put("name", name)
        put("required", required)
        put("description", description)
        put("exampleValue", toToolExampleValue(targetToolName = targetToolName))
    }

private fun ToolArgumentSpec.toToolExampleValue(targetToolName: String): JsonElement {
    val normalizedName = name.lowercase()
    return when {
        normalizedName in TOOL_EXAMPLE_OBJECT_ARGUMENTS ->
            buildJsonObject {}
        normalizedName in TOOL_EXAMPLE_ARRAY_ARGUMENTS ->
            buildJsonArray {}
        normalizedName == "confirm" ->
            JsonPrimitive("CONFIRM")
        normalizedName == "toolname" ->
            JsonPrimitive(if (targetToolName == "tools.example") "sessions.list" else targetToolName)
        normalizedName == "providerid" ->
            JsonPrimitive("local")
        normalizedName == "schedulekind" ->
            JsonPrimitive("once")
        normalizedName == "executionmode" ->
            JsonPrimitive("MAIN_SESSION")
        normalizedName == "role" ->
            JsonPrimitive("user")
        normalizedName == "category" ->
            JsonPrimitive("system")
        normalizedName == "level" ->
            JsonPrimitive("warn")
        normalizedName == "timezone" ->
            JsonPrimitive("UTC")
        normalizedName.endsWith("iso") ||
            normalizedName.contains("timestamp") ->
            JsonPrimitive("2026-03-08T00:00:00Z")
        normalizedName.contains("limit") ||
            normalizedName.contains("count") ||
            normalizedName.contains("retries") ->
            JsonPrimitive(5)
        normalizedName.contains("minutes") ->
            JsonPrimitive(15)
        normalizedName.contains("seconds") ->
            JsonPrimitive(30)
        normalizedName.startsWith("include") ||
            normalizedName.startsWith("import") ||
            normalizedName.startsWith("enable") ||
            normalizedName.startsWith("preserve") ||
            normalizedName.startsWith("copy") ||
            normalizedName.startsWith("clear") ||
            normalizedName == "dryrun" ||
            normalizedName == "availableonly" ||
            normalizedName == "requiredonly" ||
            normalizedName == "precise" ->
            JsonPrimitive(true)
        normalizedName.endsWith("id") ->
            JsonPrimitive("example-id")
        normalizedName.contains("query") ->
            JsonPrimitive("example query")
        normalizedName.contains("title") ||
            normalizedName == "name" ->
            JsonPrimitive("Example title")
        normalizedName.contains("prompt") ->
            JsonPrimitive("Example prompt")
        normalizedName.contains("summary") ->
            JsonPrimitive("Example summary")
        normalizedName.contains("content") ||
            normalizedName.contains("text") ||
            normalizedName.contains("message") ->
            JsonPrimitive("Example text")
        enumValues.isNotEmpty() ->
            JsonPrimitive(enumValues.first())
        type == ToolArgumentType.Boolean ->
            JsonPrimitive(false)
        type == ToolArgumentType.Integer ->
            JsonPrimitive(5)
        type == ToolArgumentType.Number ->
            JsonPrimitive(1.0)
        type == ToolArgumentType.Object ->
            buildJsonObject {}
        type == ToolArgumentType.Array ->
            buildJsonArray {}
        else ->
            JsonPrimitive("example-$name")
    }
}

private fun ToolDescriptor.toToolExampleMarkdown(
    requestedToolName: String,
    includeOptional: Boolean,
    exampleArguments: JsonObject,
): String =
    buildString {
        appendLine("# Tool invocation example")
        appendLine()
        appendLine("- Requested tool: `${requestedToolName.toHandoffLine()}`")
        appendLine("- Canonical tool: `${name.toHandoffLine()}`")
        appendLine("- Availability: ${availability.status.name}")
        appendLine("- Example only: true")
        appendLine("- Executes tool: false")
        appendLine("- Optional arguments included: $includeOptional")
        appendLine("- Secret values included: false")
        appendLine()
        appendLine("```json")
        appendLine(exampleArguments.toString())
        appendLine("```")
    }

private fun List<ToolDescriptor>.toToolAvailabilityStatsPayload(
    limit: Int,
    foregroundRequiredOnly: Boolean,
): JsonObject {
    val filteredTools =
        if (foregroundRequiredOnly) {
            filter { tool -> tool.foregroundRequired }
        } else {
            this
        }
    val toolsByStatus = filteredTools.groupBy { tool -> tool.availability.status }
    return buildJsonObject {
        put("availabilityStatus", JsonNull)
        put("foregroundRequiredOnly", foregroundRequiredOnly)
        put("limit", limit)
        put("toolCount", filteredTools.size)
        put("statusCount", toolsByStatus.size)
        put(
            "statuses",
            buildJsonArray {
                ToolAvailabilityStatus.entries.forEach { status ->
                    val statusTools = toolsByStatus[status].orEmpty()
                    if (statusTools.isNotEmpty()) {
                        val toolNames = statusTools.map { tool -> tool.name }.sorted()
                        add(
                            buildJsonObject {
                                put("status", status.name)
                                put("toolCount", toolNames.size)
                                put("foregroundRequiredToolCount", statusTools.count { tool -> tool.foregroundRequired })
                                put(
                                    "sampleTools",
                                    buildJsonArray {
                                        toolNames.take(limit).forEach { toolName ->
                                            add(JsonPrimitive(toolName))
                                        }
                                    },
                                )
                                if (toolNames.size > limit) {
                                    put("sampleToolsOmitted", toolNames.size - limit)
                                }
                            },
                        )
                    }
                }
            },
        )
    }
}

private fun ToolDescriptor.toToolAvailabilityMatchPayload(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("description", description)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("foregroundRequired", foregroundRequired)
        put("argumentCount", arguments.size)
        put("requiredArgumentCount", arguments.count { argument -> argument.required })
        put(
            "aliases",
            buildJsonArray {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias))
                }
            },
        )
        put(
            "requiredPermissions",
            buildJsonArray {
                requiredPermissions.forEach { permission ->
                    add(
                        buildJsonObject {
                            put("permission", permission.permission)
                            put("displayName", permission.displayName)
                        },
                    )
                }
            },
        )
    }

private fun List<ToolDescriptor>.toToolPermissionDiscoveryPayload(limit: Int): JsonObject {
    val permissionGroups =
        flatMap { tool ->
            tool.requiredPermissions.map { permission -> tool to permission }
        }.groupBy { (_, permission) -> permission.permission to permission.displayName }
            .toList()
            .sortedWith(
                compareBy<Pair<Pair<String, String>, List<Pair<ToolDescriptor, ToolPermissionRequirement>>>> { entry ->
                    entry.first.first
                }.thenBy { entry -> entry.first.second },
            )
    val limitedGroups = permissionGroups.take(limit)
    return buildJsonObject {
        put("permission", JsonNull)
        put("limit", limit)
        put("toolCount", count { tool -> tool.requiredPermissions.isNotEmpty() })
        put("uniquePermissionCount", permissionGroups.size)
        put("requirementCount", sumOf { tool -> tool.requiredPermissions.size })
        put("resultCount", limitedGroups.size)
        if (permissionGroups.size > limitedGroups.size) {
            put("omittedCount", permissionGroups.size - limitedGroups.size)
        }
        put(
            "permissions",
            buildJsonArray {
                limitedGroups.forEach { (permissionKey, entries) ->
                    val toolNames = entries.map { (tool, _) -> tool.name }.distinct().sorted()
                    add(
                        buildJsonObject {
                            put("permission", permissionKey.first)
                            put("displayName", permissionKey.second)
                            put("toolCount", toolNames.size)
                            put("requirementCount", entries.size)
                            put("availabilityStats", entries.map { (tool, _) -> tool }.toToolAvailabilityStatsByStatusPayload())
                            put(
                                "sampleTools",
                                buildJsonArray {
                                    toolNames.take(5).forEach { toolName ->
                                        add(JsonPrimitive(toolName))
                                    }
                                },
                            )
                            if (toolNames.size > 5) {
                                put("sampleToolsOmitted", toolNames.size - 5)
                            }
                        },
                    )
                }
            },
        )
    }
}

private fun List<ToolDescriptor>.toToolAvailabilityStatsByStatusPayload(): JsonArray {
    val countsByStatus = groupingBy { tool -> tool.availability.status }.eachCount()
    return buildJsonArray {
        ToolAvailabilityStatus.entries.forEach { status ->
            countsByStatus[status]?.let { count ->
                add(
                    buildJsonObject {
                        put("status", status.name)
                        put("toolCount", count)
                    },
                )
            }
        }
    }
}

private fun ToolDescriptor.toToolPermissionMatchPayload(
    matchingPermissions: List<ToolPermissionRequirement>,
): JsonObject =
    buildJsonObject {
        put("name", name)
        put("description", description)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("foregroundRequired", foregroundRequired)
        put("requiredPermissionCount", requiredPermissions.size)
        put("argumentCount", arguments.size)
        put("requiredArgumentCount", arguments.count { argument -> argument.required })
        put(
            "aliases",
            buildJsonArray {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias))
                }
            },
        )
        put(
            "matchingPermissions",
            buildJsonArray {
                matchingPermissions.forEach { permission ->
                    add(permission.toToolPermissionPayload())
                }
            },
        )
    }

private fun ToolPermissionRequirement.toToolPermissionPayload(): JsonObject =
    buildJsonObject {
        put("permission", permission)
        put("displayName", displayName)
    }

private fun ToolPermissionRequirement.matchesPermissionQuery(query: String): Boolean {
    val normalizedQuery = query.lowercase()
    return permission.lowercase().contains(normalizedQuery) ||
        displayName.lowercase().contains(normalizedQuery)
}

private fun List<ToolDescriptor>.toToolNamespaceDiscoveryPayload(limit: Int): JsonObject {
    val namespaceGroups =
        groupBy { tool -> tool.toolNamespace() }
            .toList()
            .sortedBy { (namespace, _) -> namespace }
    val limitedGroups = namespaceGroups.take(limit)
    return buildJsonObject {
        put("namespace", JsonNull)
        put("limit", limit)
        put("toolCount", size)
        put("namespaceCount", namespaceGroups.size)
        put("resultCount", limitedGroups.size)
        if (namespaceGroups.size > limitedGroups.size) {
            put("omittedCount", namespaceGroups.size - limitedGroups.size)
        }
        put(
            "namespaces",
            buildJsonArray {
                limitedGroups.forEach { (namespace, namespaceTools) ->
                    val toolNames = namespaceTools.map { tool -> tool.name }.sorted()
                    add(
                        buildJsonObject {
                            put("namespace", namespace)
                            put("toolCount", namespaceTools.size)
                            put("aliasCount", namespaceTools.sumOf { tool -> tool.aliases.size })
                            put("argumentCount", namespaceTools.sumOf { tool -> tool.arguments.size })
                            put(
                                "requiredArgumentCount",
                                namespaceTools.sumOf { tool -> tool.arguments.count { argument -> argument.required } },
                            )
                            put("requiredPermissionCount", namespaceTools.sumOf { tool -> tool.requiredPermissions.size })
                            put("availabilityStats", namespaceTools.toToolAvailabilityStatsByStatusPayload())
                            put(
                                "sampleTools",
                                buildJsonArray {
                                    toolNames.take(8).forEach { toolName ->
                                        add(JsonPrimitive(toolName))
                                    }
                                },
                            )
                            if (toolNames.size > 8) {
                                put("sampleToolsOmitted", toolNames.size - 8)
                            }
                        },
                    )
                }
            },
        )
    }
}

private fun ToolDescriptor.toToolNamespaceMatchPayload(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("namespace", toolNamespace())
        put("description", description)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("aliasCount", aliases.size)
        put("argumentCount", arguments.size)
        put("requiredArgumentCount", arguments.count { argument -> argument.required })
        put("requiredPermissionCount", requiredPermissions.size)
        put(
            "aliases",
            buildJsonArray {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias))
                }
            },
        )
    }

private fun ToolDescriptor.toolNamespace(): String = name.substringBefore(".", name)

private fun ToolDescriptor.toToolExportPayload(includeInputSchema: Boolean): JsonObject =
    buildJsonObject {
        put("name", name)
        put("namespace", toolNamespace())
        put("description", description)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("foregroundRequired", foregroundRequired)
        put("aliasCount", aliases.size)
        put("argumentCount", arguments.size)
        put("requiredArgumentCount", arguments.count { argument -> argument.required })
        put("requiredPermissionCount", requiredPermissions.size)
        put("secretValuesIncluded", false)
        put("executionResultIncluded", false)
        put("inputSchemaIncluded", includeInputSchema)
        put(
            "aliases",
            buildJsonArray {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias))
                }
            },
        )
        put(
            "arguments",
            buildJsonArray {
                arguments.forEach { argument ->
                    add(argument.toToolArgumentPayload())
                }
            },
        )
        put(
            "requiredPermissions",
            buildJsonArray {
                requiredPermissions.forEach { permission ->
                    add(permission.toToolPermissionPayload())
                }
            },
        )
        put("inputSchema", if (includeInputSchema) inputSchema else JsonNull)
    }

private fun List<ToolDescriptor>.toToolExportMarkdown(
    totalToolCount: Int,
    candidateToolCount: Int,
    requestedToolName: String?,
    namespaceFilter: String?,
    availableOnly: Boolean,
    includeInputSchemas: Boolean,
    limit: Int,
): String {
    val includedTools = this
    return buildString {
        appendLine("# Tools export")
        appendLine()
        appendLine("- Export format: $TOOL_EXPORT_FORMAT v$TOOL_EXPORT_VERSION")
        appendLine("- Tools in registry: $totalToolCount")
        appendLine("- Candidate tools after filters: $candidateToolCount")
        appendLine("- Tools included: ${includedTools.size} of up to $limit")
        appendLine("- Requested tool: ${requestedToolName?.toHandoffLine() ?: "none"}")
        appendLine("- Namespace filter: ${namespaceFilter?.toHandoffLine() ?: "none"}")
        appendLine("- Available only: $availableOnly")
        appendLine("- Input schemas included: $includeInputSchemas")
        appendLine("- Secret values included: false")
        appendLine("- Execution results included: false")
        appendLine()
        appendLine("## Exported tools")
        if (includedTools.isEmpty()) {
            appendLine("_No tools included._")
        } else {
            includedTools.forEach { tool ->
                appendLine(tool.toToolExportMarkdownLine(includeInputSchema = includeInputSchemas))
            }
        }
    }
}

private fun ToolDescriptor.toToolExportMarkdownLine(includeInputSchema: Boolean): String =
    buildString {
        append("- `")
        append(name.toHandoffLine())
        append("` namespace=")
        append(toolNamespace())
        append(" availability=")
        append(availability.status.name)
        append(" args=")
        append(arguments.size)
        append(" requiredArgs=")
        append(arguments.count { argument -> argument.required })
        append(" permissions=")
        append(requiredPermissions.size)
        append(" inputSchemaIncluded=")
        append(includeInputSchema)
        if (aliases.isNotEmpty()) {
            append(" aliases=")
            append(aliases.size)
        }
        append(" - ")
        append(description.toHandoffLine())
    }

private fun ToolDescriptor.toToolHandoffPayload(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("namespace", toolNamespace())
        put("description", description)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("foregroundRequired", foregroundRequired)
        put("aliasCount", aliases.size)
        put("argumentCount", arguments.size)
        put("requiredArgumentCount", arguments.count { argument -> argument.required })
        put("requiredPermissionCount", requiredPermissions.size)
        put(
            "aliases",
            buildJsonArray {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias))
                }
            },
        )
        put(
            "arguments",
            buildJsonArray {
                arguments.forEach { argument ->
                    add(argument.toToolArgumentPayload())
                }
            },
        )
        put(
            "requiredPermissions",
            buildJsonArray {
                requiredPermissions.forEach { permission ->
                    add(permission.toToolPermissionPayload())
                }
            },
        )
        put("inputSchemaIncluded", false)
    }

private fun List<ToolDescriptor>.toToolHandoffMarkdown(
    totalToolCount: Int,
    candidateToolCount: Int,
    namespaceFilter: String?,
    availableOnly: Boolean,
    limit: Int,
): String {
    val includedTools = this
    return buildString {
        appendLine("# Tools handoff")
        appendLine()
        appendLine("- Tools in registry: $totalToolCount")
        appendLine("- Candidate tools after filters: $candidateToolCount")
        appendLine("- Tools included: ${includedTools.size} of up to $limit")
        appendLine("- Namespace filter: ${namespaceFilter?.toHandoffLine() ?: "none"}")
        appendLine("- Available only: $availableOnly")
        appendLine()
        appendLine("## Included tools")
        if (includedTools.isEmpty()) {
            appendLine("_No tools included._")
        } else {
            includedTools.forEach { tool ->
                appendLine(tool.toToolHandoffMarkdownLine())
            }
        }
    }
}

private fun ToolDescriptor.toToolHandoffMarkdownLine(): String =
    buildString {
        append("- `")
        append(name.toHandoffLine())
        append("` namespace=")
        append(toolNamespace())
        append(" availability=")
        append(availability.status.name)
        append(" args=")
        append(arguments.size)
        append(" requiredArgs=")
        append(arguments.count { argument -> argument.required })
        if (aliases.isNotEmpty()) {
            append(" aliases=")
            append(aliases.size)
        }
        if (requiredPermissions.isNotEmpty()) {
            append(" permissions=")
            append(requiredPermissions.joinToString(",") { permission -> permission.permission }.toHandoffLine())
        }
        append(" - ")
        append(description.toHandoffLine())
    }

private fun List<ToolDescriptor>.toToolDoctorIssues(namespaceFilter: String?): List<ToolDoctorIssue> {
    val tools = this
    return buildList {
        if (tools.isEmpty()) {
            val filtered = namespaceFilter != null
            add(
                ToolDoctorIssue(
                    id =
                        if (filtered) {
                            "namespace:${namespaceFilter.orEmpty()}:tool.namespace.empty"
                        } else {
                            "registry:tool.registry.empty"
                        },
                    severity =
                        if (filtered) {
                            "Warning"
                        } else {
                            "Error"
                        },
                    code =
                        if (filtered) {
                            "tool.namespace.empty"
                        } else {
                            "tool.registry.empty"
                        },
                    toolName = null,
                    namespace = namespaceFilter,
                    availabilityStatus = null,
                    summary =
                        (
                            if (filtered) {
                                "No typed tools match namespace ${namespaceFilter.orEmpty()}."
                            } else {
                                "The typed tool registry is empty."
                            }
                        ).toToolDoctorText(),
                    action =
                        (
                            if (filtered) {
                                "Run tools.namespaces or remove the namespace filter before selecting a tool."
                            } else {
                                "Wire built-in tools before relying on tool dispatch."
                            }
                        ).toToolDoctorText(),
                ),
            )
            return@buildList
        }

        tools.forEach { tool ->
            val status = tool.availability.status
            if (status != ToolAvailabilityStatus.Available) {
                add(
                    ToolDoctorIssue(
                        id = "${tool.name}:${status.toToolDoctorCode()}",
                        severity = status.toToolDoctorSeverity(),
                        code = status.toToolDoctorCode(),
                        toolName = tool.name,
                        namespace = tool.toolNamespace(),
                        availabilityStatus = status.name,
                        summary = "Tool ${tool.name} is ${status.name}.".toToolDoctorText(),
                        action = status.toToolDoctorAction().toToolDoctorText(),
                        detail = tool.availability.reason?.toToolDoctorText(),
                    ),
                )
            }
        }

        if (tools.none { tool -> tool.availability.status == ToolAvailabilityStatus.Available }) {
            val namespace = namespaceFilter ?: tools.firstOrNull()?.toolNamespace()
            add(
                ToolDoctorIssue(
                    id =
                        if (namespace != null) {
                            "namespace:$namespace:tool.none_available"
                        } else {
                            "registry:tool.none_available"
                        },
                    severity = "Error",
                    code = "tool.none_available",
                    toolName = null,
                    namespace = namespace,
                    availabilityStatus = null,
                    summary =
                        (
                            if (namespace != null) {
                                "No candidate tools in namespace $namespace are currently available."
                            } else {
                                "No candidate tools are currently available."
                            }
                        ).toToolDoctorText(),
                    action = "Grant required permissions, enable disabled features, or choose a different namespace.".toToolDoctorText(),
                ),
            )
        }
    }
}

private fun ToolAvailabilityStatus.toToolDoctorSeverity(): String =
    when (this) {
        ToolAvailabilityStatus.Available -> "Info"
        ToolAvailabilityStatus.Unavailable,
        ToolAvailabilityStatus.DisabledByConfig,
        -> "Error"
        ToolAvailabilityStatus.PermissionRequired,
        ToolAvailabilityStatus.ForegroundRequired,
        -> "Warning"
    }

private fun ToolAvailabilityStatus.toToolDoctorCode(): String =
    when (this) {
        ToolAvailabilityStatus.Available -> "tool.availability.available"
        ToolAvailabilityStatus.Unavailable -> "tool.availability.unavailable"
        ToolAvailabilityStatus.PermissionRequired -> "tool.availability.permission_required"
        ToolAvailabilityStatus.ForegroundRequired -> "tool.availability.foreground_required"
        ToolAvailabilityStatus.DisabledByConfig -> "tool.availability.disabled_by_config"
    }

private fun ToolAvailabilityStatus.toToolDoctorAction(): String =
    when (this) {
        ToolAvailabilityStatus.Available -> "No action required."
        ToolAvailabilityStatus.Unavailable -> "Inspect the availability reason and enable the platform/runtime prerequisite."
        ToolAvailabilityStatus.PermissionRequired -> "Grant the required Android permission(s) or choose a tool that does not require them."
        ToolAvailabilityStatus.ForegroundRequired -> "Run the tool from an interactive foreground app context or use a background-safe tool."
        ToolAvailabilityStatus.DisabledByConfig -> "Enable the related feature/configuration or select an alternative tool."
    }

private fun ToolDescriptor.toToolDoctorCheckPayload(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("namespace", toolNamespace())
        put("description", description)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("foregroundRequired", foregroundRequired)
        put("aliasCount", aliases.size)
        put("argumentCount", arguments.size)
        put("requiredArgumentCount", arguments.count { argument -> argument.required })
        put("requiredPermissionCount", requiredPermissions.size)
        put("inputSchemaIncluded", false)
        put(
            "requiredPermissions",
            buildJsonArray {
                requiredPermissions.forEach { permission ->
                    add(permission.toToolPermissionPayload())
                }
            },
        )
    }

private fun ToolDoctorIssue.toToolDoctorPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("severity", severity)
        put("code", code)
        put("toolName", toolName?.let(::JsonPrimitive) ?: JsonNull)
        put("namespace", namespace?.let(::JsonPrimitive) ?: JsonNull)
        put("availabilityStatus", availabilityStatus?.let(::JsonPrimitive) ?: JsonNull)
        put("summary", summary)
        put("action", action)
        put("detail", detail?.let(::JsonPrimitive) ?: JsonNull)
    }

private fun List<ToolDoctorIssue>.toToolDoctorStatus(): String =
    when {
        any { issue -> issue.severity == "Error" } -> "ERROR"
        any { issue -> issue.severity == "Warning" } -> "WARN"
        else -> "OK"
    }

private fun List<ToolDoctorIssue>.toToolDoctorMarkdown(
    status: String,
    totalToolCount: Int,
    candidateToolCount: Int,
    issueCount: Int,
    namespaceFilter: String?,
    limit: Int,
): String {
    val includedIssues = this
    return buildString {
        appendLine("# Tools doctor")
        appendLine()
        appendLine("- Status: $status")
        appendLine("- Tools in registry: $totalToolCount")
        appendLine("- Candidate tools after filters: $candidateToolCount")
        appendLine("- Namespace filter: ${namespaceFilter?.toHandoffLine() ?: "none"}")
        appendLine("- Issues included: ${includedIssues.size} of $issueCount")
        appendLine("- Limit: $limit")
        appendLine("- Input schemas omitted: true")
        appendLine()
        appendLine("## Issues")
        if (includedIssues.isEmpty()) {
            appendLine("_No tool issues found._")
        } else {
            includedIssues.forEach { issue ->
                appendLine(issue.toToolDoctorMarkdownLine())
            }
        }
    }
}

private fun ToolDoctorIssue.toToolDoctorMarkdownLine(): String =
    buildString {
        append("- ")
        append(severity)
        append(" `")
        append(toolName?.toHandoffLine() ?: "registry")
        append("`")
        namespace?.let { namespace ->
            append(" namespace=")
            append(namespace.toHandoffLine())
        }
        append(" code=")
        append(code)
        availabilityStatus?.let { status ->
            append(" availability=")
            append(status)
        }
        append(": ")
        append(summary.toHandoffLine())
        detail?.let { detail ->
            append(" detail=")
            append(detail.toHandoffLine())
        }
        append(" Action: ")
        append(action.toHandoffLine())
    }

private fun String.toToolDoctorText(): String = toHandoffLine().take(TOOL_DOCTOR_TEXT_MAX_CHARS)

private fun ToolDescriptor.toToolDescriptorPayload(includeInputSchema: Boolean): JsonObject =
    buildJsonObject {
        put("name", name)
        put("description", description)
        put(
            "aliases",
            buildJsonArray {
                aliases.forEach { alias ->
                    add(JsonPrimitive(alias))
                }
            },
        )
        put("foregroundRequired", foregroundRequired)
        put("availabilityStatus", availability.status.name)
        put("availabilityReason", availability.reason?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "requiredPermissions",
            buildJsonArray {
                requiredPermissions.forEach { permission ->
                    add(
                        buildJsonObject {
                            put("permission", permission.permission)
                            put("displayName", permission.displayName)
                        },
                    )
                }
            },
        )
        put(
            "arguments",
            buildJsonArray {
                arguments.forEach { argument ->
                    add(
                        buildJsonObject {
                            put("name", argument.name)
                            put("required", argument.required)
                            put("description", argument.description)
                        },
                    )
                }
            },
        )
        put("inputSchema", if (includeInputSchema) inputSchema else JsonNull)
    }


private data class ToolDoctorIssue(
    val id: String,
    val severity: String,
    val code: String,
    val toolName: String?,
    val namespace: String?,
    val availabilityStatus: String?,
    val summary: String,
    val action: String,
    val detail: String? = null,
)


internal const val TOOL_ARGUMENTS_DEFAULT_LIMIT = 50
internal const val TOOL_ARGUMENTS_MAX_LIMIT = 100
internal const val TOOL_AVAILABILITY_DEFAULT_LIMIT = 50
internal const val TOOL_AVAILABILITY_MAX_LIMIT = 100
internal const val TOOL_DOCTOR_CHECK_MAX_LIMIT = 20
internal const val TOOL_DOCTOR_DEFAULT_LIMIT = 20
internal const val TOOL_DOCTOR_MAX_LIMIT = 50
internal const val TOOL_DOCTOR_TEXT_MAX_CHARS = 500
internal const val TOOL_EXPORT_FORMAT = "androidclaw.tools.export.v1"
internal const val TOOL_EXPORT_VERSION = 1
internal const val TOOL_EXPORT_DEFAULT_LIMIT = 100
internal const val TOOL_EXPORT_MAX_LIMIT = 200
internal const val TOOL_HANDOFF_DEFAULT_LIMIT = 12
internal const val TOOL_HANDOFF_MAX_LIMIT = 30
internal const val TOOL_PERMISSIONS_DEFAULT_LIMIT = 50
internal const val TOOL_PERMISSIONS_MAX_LIMIT = 100
internal const val TOOL_NAMESPACES_DEFAULT_LIMIT = 50
internal const val TOOL_NAMESPACES_MAX_LIMIT = 100
internal const val TOOL_SEARCH_DEFAULT_LIMIT = 20
internal const val TOOL_SEARCH_MAX_LIMIT = 100
internal const val TOOL_NOTIFICATION_CHANNEL_ID = "androidclaw.tools"
internal val TOOL_VALIDATE_RESERVED_ARGUMENT_FIELDS = setOf("toolName", "name", "arguments")
internal val TOOL_EXAMPLE_OBJECT_ARGUMENTS =
    setOf(
        "arguments",
        "backup",
        "export",
        "payload",
        "settings",
    )
internal val TOOL_EXAMPLE_ARRAY_ARGUMENTS =
    setOf(
        "events",
        "exports",
        "memories",
        "messages",
        "providers",
        "sessions",
        "skills",
        "tasks",
    )

