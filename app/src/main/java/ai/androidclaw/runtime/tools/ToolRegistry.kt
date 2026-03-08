package ai.androidclaw.runtime.tools

import kotlinx.serialization.json.JsonObject

data class ToolDescriptor(
    val name: String,
    val description: String,
    val foregroundRequired: Boolean = false,
)

data class ToolExecutionResult(
    val summary: String,
    val payload: JsonObject,
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
        val entry = entries[name] ?: error("Unknown tool: $name")
        return entry.handler(arguments)
    }
}

