package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.runtime.tools.TOOL_REGISTRY_ALIAS_LIST_MAX_ITEMS
import ai.androidclaw.runtime.tools.TOOL_REGISTRY_ARGUMENT_LIST_MAX_ITEMS
import ai.androidclaw.runtime.tools.TOOL_REGISTRY_ARGUMENT_NAME_MAX_CHARS
import ai.androidclaw.runtime.tools.TOOL_REGISTRY_NAME_MAX_CHARS
import ai.androidclaw.runtime.tools.TOOL_REGISTRY_PERMISSION_LIST_MAX_ITEMS
import ai.androidclaw.runtime.tools.ToolDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal const val MAX_MODEL_TOOL_INPUT_SCHEMA_DEPTH = 8
internal const val MAX_MODEL_TOOL_INPUT_SCHEMA_ENTRIES = 50
internal const val MAX_MODEL_TOOL_INPUT_SCHEMA_STRING_CHARS = 1_000

internal fun List<ToolDescriptor>.toBoundedModelToolDescriptors(): List<ToolDescriptor> =
    take(MAX_PROMPT_TOOLS).map { descriptor ->
        descriptor.copy(
            description = descriptor.description.toModelToolText(MAX_PROMPT_TOOL_DESCRIPTION_CHARS),
            aliases =
                descriptor.aliases
                    .take(TOOL_REGISTRY_ALIAS_LIST_MAX_ITEMS.coerceAtMost(MAX_PROMPT_TOOL_ALIASES))
                    .map { alias -> alias.toModelToolText(MAX_PROMPT_TOOL_ALIAS_CHARS) },
            requiredPermissions =
                descriptor.requiredPermissions
                    .take(TOOL_REGISTRY_PERMISSION_LIST_MAX_ITEMS)
                    .map { permission ->
                        permission.copy(
                            permission = permission.permission.toModelToolText(TOOL_REGISTRY_NAME_MAX_CHARS),
                            displayName = permission.displayName.toModelToolText(TOOL_REGISTRY_NAME_MAX_CHARS),
                        )
                    },
            availability =
                descriptor.availability.copy(
                    reason = descriptor.availability.reason?.toModelToolText(MAX_MODEL_TOOL_INPUT_SCHEMA_STRING_CHARS),
                ),
            arguments =
                descriptor.arguments
                    .take(TOOL_REGISTRY_ARGUMENT_LIST_MAX_ITEMS)
                    .map { argument ->
                        argument.copy(
                            description =
                                argument.description.toModelToolText(MAX_MODEL_TOOL_INPUT_SCHEMA_STRING_CHARS),
                        )
                    },
            inputSchema = descriptor.inputSchema.toBoundedModelToolInputSchema(),
        )
    }

private fun JsonObject.toBoundedModelToolInputSchema(): JsonObject =
    toBoundedModelToolSchemaElement(depth = 0) as? JsonObject ?: buildJsonObject {
        put("type", JsonPrimitive("object"))
    }

private fun JsonElement.toBoundedModelToolSchemaElement(depth: Int): JsonElement {
    if (depth >= MAX_MODEL_TOOL_INPUT_SCHEMA_DEPTH) {
        return JsonPrimitive("[omitted]")
    }
    return when (this) {
        is JsonObject ->
            buildJsonObject {
                entries
                    .take(MAX_MODEL_TOOL_INPUT_SCHEMA_ENTRIES)
                    .forEach { (key, value) ->
                        put(
                            key.toModelToolText(TOOL_REGISTRY_ARGUMENT_NAME_MAX_CHARS),
                            value.toBoundedModelToolSchemaElement(depth + 1),
                        )
                    }
            }

        is JsonArray ->
            buildJsonArray {
                take(MAX_MODEL_TOOL_INPUT_SCHEMA_ENTRIES).forEach { value ->
                    add(value.toBoundedModelToolSchemaElement(depth + 1))
                }
            }

        JsonNull -> JsonNull
        is JsonPrimitive -> toBoundedModelToolSchemaPrimitive()
    }
}

private fun JsonPrimitive.toBoundedModelToolSchemaPrimitive(): JsonPrimitive =
    if (isString) {
        JsonPrimitive(content.toModelToolText(MAX_MODEL_TOOL_INPUT_SCHEMA_STRING_CHARS))
    } else {
        this
    }

private fun String.toModelToolText(maxChars: Int): String = take(maxChars)
