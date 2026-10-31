package ai.androidclaw.runtime.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal fun JsonObject.optionalText(field: String): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.trim()?.ifBlank { null }
}

internal fun JsonObject.optionalRawText(field: String): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { value -> value.isNotEmpty() }
}

internal fun JsonObject.optionalBoolean(
    field: String,
    defaultValue: Boolean = false,
): Boolean = optionalBooleanOrNull(field) ?: defaultValue

internal fun JsonObject.optionalBooleanOrNull(field: String): Boolean? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return when (primitive.contentOrNull?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}

internal fun JsonObject.optionalInt(
    field: String,
    defaultValue: Int,
): Int = optionalLongOrNull(field)?.toInt() ?: defaultValue

internal fun JsonObject.optionalLongOrNull(field: String): Long? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return primitive.longOrNull
        ?: primitive.contentOrNull
            ?.trim()
            ?.toDoubleOrNull()
            ?.toLong()
}

/** Reads an integer argument and clamps it into [min, max]. */
internal fun JsonObject.boundedInt(
    field: String,
    defaultValue: Int,
    min: Int,
    max: Int,
): Int = (optionalLongOrNull(field) ?: defaultValue.toLong()).coerceIn(min.toLong(), max.toLong()).toInt()

internal fun JsonObject.requireText(
    field: String,
    toolName: String,
): String = optionalText(field) ?: throw IllegalArgumentException("$toolName requires a non-empty $field.")

internal fun stringArg(
    name: String,
    description: String,
    required: Boolean = false,
    enumValues: List<String> = emptyList(),
): ToolArgumentSpec =
    ToolArgumentSpec(
        name = name,
        required = required,
        description = description,
        type = ToolArgumentType.String,
        enumValues = enumValues,
        validate = true,
        sensitive = false,
    )

internal fun intArg(
    name: String,
    description: String,
    required: Boolean = false,
): ToolArgumentSpec =
    ToolArgumentSpec(
        name = name,
        required = required,
        description = description,
        type = ToolArgumentType.Integer,
        validate = true,
        sensitive = false,
    )

internal fun boolArg(
    name: String,
    description: String,
): ToolArgumentSpec =
    ToolArgumentSpec(
        name = name,
        description = description,
        type = ToolArgumentType.Boolean,
        validate = true,
        sensitive = false,
    )

/** Origins for tools the model may call in interactive turns. */
internal val INTERACTIVE_ORIGINS: Set<ToolInvocationOrigin> =
    setOf(
        ToolInvocationOrigin.Model,
        ToolInvocationOrigin.SlashCommand,
        ToolInvocationOrigin.Internal,
    )

/** Origins for tools the model may also call from scheduled automation runs. */
internal val INTERACTIVE_AND_SCHEDULED_ORIGINS: Set<ToolInvocationOrigin> =
    INTERACTIVE_ORIGINS + ToolInvocationOrigin.ScheduledModel

/** Origins for slash-command helpers that are never offered to the model. */
internal val SLASH_COMMAND_ORIGINS: Set<ToolInvocationOrigin> =
    setOf(
        ToolInvocationOrigin.SlashCommand,
        ToolInvocationOrigin.Internal,
    )

internal fun toolFailure(
    errorCode: String,
    summary: String,
    payload: JsonObject = buildJsonObject {},
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = summary,
        errorCode = errorCode,
        payload =
            buildJsonObject {
                put("errorCode", errorCode)
                payload.forEach { (key, value) -> put(key, value) }
            },
    )

internal fun toolSuccess(
    summary: String,
    payload: JsonObject = buildJsonObject {},
): ToolExecutionResult = ToolExecutionResult.success(summary = summary, payload = payload)

internal fun String.clip(maxChars: Int): String =
    if (length <= maxChars) {
        this
    } else {
        take((maxChars - 1).coerceAtLeast(0)).trimEnd() + "…"
    }

internal fun String.singleLine(): String =
    lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" ")
