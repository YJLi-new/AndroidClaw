package ai.androidclaw.runtime.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

data class ToolDescriptor(
    val name: String,
    val description: String,
    val foregroundRequired: Boolean = false,
)

data class ToolExecutionResult(
    val summary: String,
    val payload: JsonObject,
    val success: Boolean = true,
)

class ToolRegistry(
    tools: List<Entry>,
) {
    data class Entry(
        val descriptor: ToolDescriptor,
        val handler: suspend (JsonObject) -> ToolExecutionResult,
    )

    private val entries = tools.associateBy { it.descriptor.name }

    fun hasTool(name: String): Boolean = entries.containsKey(name)

    fun descriptors(): List<ToolDescriptor> = entries.values
        .map { it.descriptor }
        .sortedBy { it.name }

    suspend fun execute(name: String, arguments: JsonObject): ToolExecutionResult {
        val entry = entries[name] ?: return ToolExecutionResult(
            summary = "Unknown tool: $name",
            payload = buildJsonObject {
                put("error", "UNKNOWN_TOOL")
                put("toolName", name)
            },
            success = false,
        )
        return try {
            entry.handler(arguments)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolExecutionResult(
                summary = error.message ?: "Tool $name failed.",
                payload = buildJsonObject {
                    put("error", "TOOL_EXECUTION_FAILED")
                    put("toolName", name)
                    put("message", error.message ?: "Unknown error")
                },
                success = false,
            )
        }
    }
}
