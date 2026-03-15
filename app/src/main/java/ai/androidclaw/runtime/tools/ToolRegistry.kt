package ai.androidclaw.runtime.tools

import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.runtime.providers.ModelRunMode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

data class ToolArgumentSpec(
    val name: String,
    val required: Boolean = false,
    val description: String = "",
)

data class ToolPermissionRequirement(
    val permission: String,
    val displayName: String = permission,
)

enum class ToolAvailabilityStatus {
    Available,
    Unavailable,
    PermissionRequired,
    ForegroundRequired,
    DisabledByConfig,
}

data class ToolAvailability(
    val status: ToolAvailabilityStatus = ToolAvailabilityStatus.Available,
    val reason: String? = null,
)

data class ToolDescriptor(
    val name: String,
    val description: String,
    val aliases: List<String> = emptyList(),
    val foregroundRequired: Boolean = false,
    val requiredPermissions: List<ToolPermissionRequirement> = emptyList(),
    val availability: ToolAvailability = ToolAvailability(),
    val arguments: List<ToolArgumentSpec> = emptyList(),
    val inputSchema: JsonObject = buildToolInputSchema(arguments),
)

data class ToolExecutionResult(
    val summary: String,
    val payload: JsonObject,
    val success: Boolean = true,
    val errorCode: String? = null,
) {
    companion object {
        fun success(
            summary: String,
            payload: JsonObject,
        ): ToolExecutionResult {
            return ToolExecutionResult(
                summary = summary,
                payload = payload,
                success = true,
            )
        }

        fun failure(
            summary: String,
            errorCode: String,
            payload: JsonObject,
        ): ToolExecutionResult {
            return ToolExecutionResult(
                summary = summary,
                payload = payload,
                success = false,
                errorCode = errorCode,
            )
        }
    }
}

enum class ToolInvocationOrigin {
    Model,
    SlashCommand,
    ScheduledModel,
    Internal,
}

data class ToolExecutionContext(
    val sessionId: String?,
    val taskRunId: String?,
    val origin: ToolInvocationOrigin,
    val runMode: ModelRunMode?,
    val requestedName: String,
    val canonicalName: String,
    val requestId: String?,
    val activeSkillId: String? = null,
) {
    companion object {
        fun internal(
            requestedName: String,
            canonicalName: String = requestedName,
            sessionId: String? = null,
            taskRunId: String? = null,
            requestId: String? = null,
            runMode: ModelRunMode? = null,
            activeSkillId: String? = null,
        ): ToolExecutionContext {
            return ToolExecutionContext(
                sessionId = sessionId,
                taskRunId = taskRunId,
                origin = ToolInvocationOrigin.Internal,
                runMode = runMode,
                requestedName = requestedName,
                canonicalName = canonicalName,
                requestId = requestId,
                activeSkillId = activeSkillId,
            )
        }
    }
}

class ToolRegistry(
    tools: List<Entry>,
    private val eventLogger: suspend (EventLevel, String, String?) -> Unit = { _, _, _ -> },
) {
    data class Entry(
        val descriptor: ToolDescriptor,
        val availabilityProvider: () -> ToolAvailability = { descriptor.availability },
        val handler: suspend (ToolExecutionContext, JsonObject) -> ToolExecutionResult,
    ) {
        fun resolvedDescriptor(): ToolDescriptor = descriptor.copy(availability = availabilityProvider())
    }

    private val canonicalEntries = tools.sortedBy { it.descriptor.name }
    private val entriesByName = buildMap {
        canonicalEntries.forEach { entry ->
            val canonicalName = entry.descriptor.name
            require(put(canonicalName, entry) == null) {
                "Duplicate tool name: $canonicalName"
            }
            entry.descriptor.aliases.forEach { alias ->
                require(alias != canonicalName) {
                    "Tool alias must differ from canonical name: $canonicalName"
                }
                require(put(alias, entry) == null) {
                    "Duplicate tool alias: $alias"
                }
            }
        }
    }

    fun hasTool(name: String): Boolean = entriesByName.containsKey(name)

    fun findDescriptor(name: String): ToolDescriptor? = entriesByName[name]?.resolvedDescriptor()

    fun descriptors(): List<ToolDescriptor> = canonicalEntries
        .map { it.resolvedDescriptor() }

    suspend fun execute(
        context: ToolExecutionContext,
        arguments: JsonObject,
    ): ToolExecutionResult {
        val entry = entriesByName[context.requestedName] ?: return ToolExecutionResult.failure(
            summary = "Unknown tool: ${context.requestedName}",
            errorCode = "UNKNOWN_TOOL",
            payload = buildJsonObject {
                put("errorCode", "UNKNOWN_TOOL")
                put("toolName", context.requestedName)
            },
        ).also { result ->
            logToolEvent(
                level = EventLevel.Warn,
                message = "Tool ${context.requestedName} failed before execution.",
                context = context,
                result = result,
            )
        }
        val descriptor = entry.resolvedDescriptor()
        val resolvedContext = context.copy(canonicalName = descriptor.name)
        validateArguments(
            descriptor = descriptor,
            arguments = arguments,
        )?.let { result ->
            logToolEvent(
                level = EventLevel.Warn,
                message = "Tool ${descriptor.name} failed argument validation.",
                context = resolvedContext,
                result = result,
            )
            return result
        }
        availabilityFailure(
            descriptor = descriptor,
            requestedName = resolvedContext.requestedName,
        )?.let { result ->
            logToolEvent(
                level = EventLevel.Warn,
                message = "Tool ${descriptor.name} is unavailable for the current execution context.",
                context = resolvedContext,
                result = result,
            )
            return result
        }
        logToolEvent(
            level = EventLevel.Info,
            message = "Tool ${descriptor.name} started.",
            context = resolvedContext,
            result = null,
        )
        return try {
            entry.handler(resolvedContext, arguments).also { result ->
                logToolEvent(
                    level = if (result.success) EventLevel.Info else EventLevel.Warn,
                    message = if (result.success) {
                        "Tool ${descriptor.name} completed."
                    } else {
                        "Tool ${descriptor.name} returned a structured failure."
                    },
                    context = resolvedContext,
                    result = result,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolExecutionResult.failure(
                summary = error.message ?: "Tool ${descriptor.name} failed.",
                errorCode = "TOOL_EXECUTION_FAILED",
                payload = buildJsonObject {
                    put("errorCode", "TOOL_EXECUTION_FAILED")
                    put("toolName", descriptor.name)
                    put("message", error.message ?: "Unknown error")
                },
            ).also { result ->
                logToolEvent(
                    level = EventLevel.Error,
                    message = "Tool ${descriptor.name} threw an exception.",
                    context = resolvedContext,
                    result = result,
                )
            }
        }
    }

    private fun validateArguments(
        descriptor: ToolDescriptor,
        arguments: JsonObject,
    ): ToolExecutionResult? {
        val missingRequiredArguments = descriptor.arguments
            .filter { it.required }
            .mapNotNull { argument ->
                val value = arguments[argument.name]
                argument.name.takeIf {
                    value == null || (value is JsonPrimitive && value.content.isBlank())
                }
            }
        if (missingRequiredArguments.isEmpty()) {
            return null
        }
        return ToolExecutionResult.failure(
            summary = "Missing required arguments for ${descriptor.name}: ${missingRequiredArguments.joinToString()}",
            errorCode = "INVALID_ARGUMENTS",
            payload = buildJsonObject {
                put("errorCode", "INVALID_ARGUMENTS")
                put("toolName", descriptor.name)
                put("missingArguments", missingRequiredArguments.toStringJsonArray())
                put("providedArguments", arguments.keys.sorted().toStringJsonArray())
            },
        )
    }

    private fun availabilityFailure(
        descriptor: ToolDescriptor,
        requestedName: String,
    ): ToolExecutionResult? {
        val availability = descriptor.availability
        val errorCode = when (availability.status) {
            ToolAvailabilityStatus.Available -> return null
            ToolAvailabilityStatus.Unavailable -> "TOOL_UNAVAILABLE"
            ToolAvailabilityStatus.PermissionRequired -> "PERMISSION_REQUIRED"
            ToolAvailabilityStatus.ForegroundRequired -> "FOREGROUND_REQUIRED"
            ToolAvailabilityStatus.DisabledByConfig -> "DISABLED_BY_CONFIG"
        }
        val summary = availability.reason ?: when (availability.status) {
            ToolAvailabilityStatus.Available -> error("Handled above.")
            ToolAvailabilityStatus.Unavailable -> "Tool ${descriptor.name} is currently unavailable."
            ToolAvailabilityStatus.PermissionRequired -> "Tool ${descriptor.name} requires additional permission."
            ToolAvailabilityStatus.ForegroundRequired -> "Tool ${descriptor.name} requires the app to be in the foreground."
            ToolAvailabilityStatus.DisabledByConfig -> "Tool ${descriptor.name} is disabled by configuration."
        }
        return ToolExecutionResult.failure(
            summary = summary,
            errorCode = errorCode,
            payload = buildJsonObject {
                put("errorCode", errorCode)
                put("requestedName", requestedName)
                put("toolName", descriptor.name)
                put("availabilityStatus", availability.status.name)
                put("requiredPermissions", descriptor.requiredPermissions.toPermissionJsonArray())
            },
        )
    }

    private suspend fun logToolEvent(
        level: EventLevel,
        message: String,
        context: ToolExecutionContext,
        result: ToolExecutionResult?,
    ) {
        eventLogger(
            level,
            message,
            buildJsonObject {
                put("requestedName", context.requestedName)
                put("canonicalName", context.canonicalName)
                put("origin", context.origin.name)
                put("sessionId", context.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                put("taskRunId", context.taskRunId?.let(::JsonPrimitive) ?: JsonNull)
                put("runMode", context.runMode?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("requestId", context.requestId?.let(::JsonPrimitive) ?: JsonNull)
                put("activeSkillId", context.activeSkillId?.let(::JsonPrimitive) ?: JsonNull)
                put("success", result?.success?.let(::JsonPrimitive) ?: JsonNull)
                put("errorCode", result?.errorCode?.let(::JsonPrimitive) ?: JsonNull)
            }.toString(),
        )
    }
}

private fun List<String>.toStringJsonArray(): JsonArray {
    return buildJsonArray {
        forEach { add(JsonPrimitive(it)) }
    }
}

private fun buildToolInputSchema(arguments: List<ToolArgumentSpec>): JsonObject {
    return buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                arguments.forEach { argument ->
                    put(
                        argument.name,
                        buildJsonObject {
                            put("type", "string")
                            argument.description.takeIf { it.isNotBlank() }?.let { put("description", it) }
                        },
                    )
                }
            },
        )
        put(
            "required",
            buildJsonArray {
                arguments.filter { it.required }.forEach { argument ->
                    add(JsonPrimitive(argument.name))
                }
            },
        )
    }
}

private fun List<ToolPermissionRequirement>.toPermissionJsonArray(): JsonArray {
    return buildJsonArray {
        forEach { permission ->
            add(
                buildJsonObject {
                    put("permission", permission.permission)
                    put("displayName", permission.displayName)
                },
            )
        }
    }
}
