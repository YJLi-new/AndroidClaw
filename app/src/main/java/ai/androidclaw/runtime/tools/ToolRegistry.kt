package ai.androidclaw.runtime.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class ToolRegistry(
    tools: List<Entry>,
) {
    data class Entry(
        val descriptor: ToolDescriptor,
        val availabilityProvider: () -> ToolAvailability = { descriptor.availability },
        val handler: suspend (JsonObject) -> ToolExecutionResult,
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

    suspend fun execute(name: String, arguments: JsonObject): ToolExecutionResult {
        val entry = entriesByName[name] ?: return ToolExecutionResult.failure(
            summary = "Unknown tool: $name",
            errorCode = "UNKNOWN_TOOL",
            payload = buildJsonObject {
                put("errorCode", "UNKNOWN_TOOL")
                put("toolName", name)
            },
        )
        val descriptor = entry.resolvedDescriptor()
        validateArguments(
            descriptor = descriptor,
            arguments = arguments,
        )?.let { return it }
        availabilityFailure(
            descriptor = descriptor,
            requestedName = name,
        )?.let { return it }
        return try {
            entry.handler(arguments)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolExecutionResult.failure(
                summary = error.message ?: "Tool $name failed.",
                errorCode = "TOOL_EXECUTION_FAILED",
                payload = buildJsonObject {
                    put("errorCode", "TOOL_EXECUTION_FAILED")
                    put("toolName", name)
                    put("message", error.message ?: "Unknown error")
                },
            )
        }
    }

    private fun validateArguments(
        descriptor: ToolDescriptor,
        arguments: JsonObject,
    ): ToolExecutionResult? {
        val missingRequiredArguments = descriptor.arguments
            .filter { it.required }
            .mapNotNull { argument ->
                val value = arguments[argument.name]?.jsonPrimitive?.content
                argument.name.takeIf { value.isNullOrBlank() }
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
}

private fun List<String>.toStringJsonArray(): JsonArray {
    return buildJsonArray {
        forEach { add(JsonPrimitive(it)) }
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
