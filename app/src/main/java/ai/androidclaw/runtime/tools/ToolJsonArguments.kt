package ai.androidclaw.runtime.tools

import ai.androidclaw.data.model.MessageRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun kotlinx.serialization.json.JsonObject.optionalText(field: String): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.trim()?.ifBlank { null }
}

internal fun kotlinx.serialization.json.JsonObject.optionalRawText(field: String): String? {
    val primitive = this[field] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { value -> value.isNotBlank() }
}

internal fun kotlinx.serialization.json.JsonObject.optionalBoolean(
    field: String,
    defaultValue: Boolean = false,
): Boolean {
    val primitive = this[field] as? JsonPrimitive ?: return defaultValue
    return when (primitive.contentOrNull?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> defaultValue
    }
}

internal fun kotlinx.serialization.json.JsonObject.optionalInt(
    field: String,
    defaultValue: Int,
): Int = optionalText(field)?.toIntOrNull() ?: defaultValue
