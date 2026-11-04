package ai.androidclaw.feature.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Display form of a persisted tool result row (`<summary>\n<payload json>`):
 * the first summary line is the card headline, the payload stays collapsed.
 */
internal data class ToolResultView(
    val headline: String,
    val summary: String,
    val payload: String?,
    val errorCode: String?,
)

internal fun parseToolResultView(result: String): ToolResultView {
    val trimmed = result.trim()
    val lastBreak = trimmed.lastIndexOf('\n')
    val tail = if (lastBreak >= 0) trimmed.substring(lastBreak + 1).trim() else trimmed
    val payloadObject = tail.takeIf { it.startsWith("{") }?.let(::parseJsonObjectOrNull)
    val summary =
        when {
            payloadObject == null -> trimmed
            lastBreak >= 0 -> trimmed.substring(0, lastBreak).trim()
            else -> ""
        }
    val errorCode =
        (payloadObject?.get("errorCode") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf(String::isNotBlank)
    return ToolResultView(
        headline =
            summary
                .lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
                .orEmpty(),
        summary = summary,
        payload = payloadObject?.takeIf { it.isNotEmpty() }?.let(::prettyRedactedJson),
        errorCode = errorCode,
    )
}

/** Pretty-prints JSON arguments with secrets masked; non-JSON text is returned as is. */
internal fun formatToolArguments(arguments: String): String {
    val trimmed = arguments.trim()
    if (!trimmed.startsWith("{")) return trimmed
    return parseJsonObjectOrNull(trimmed)?.let(::prettyRedactedJson) ?: trimmed
}

private fun parseJsonObjectOrNull(text: String): JsonObject? = runCatching { toolDisplayJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

private fun prettyRedactedJson(element: JsonObject): String = toolDisplayJson.encodeToString(JsonElement.serializer(), element.redacted())

private fun JsonElement.redacted(): JsonElement =
    when (this) {
        is JsonObject ->
            JsonObject(
                mapValues { (key, value) ->
                    if (key.isSensitiveToolKey() && value is JsonPrimitive && value.isString) {
                        JsonPrimitive(TOOL_REDACTED_VALUE)
                    } else {
                        value.redacted()
                    }
                },
            )
        is JsonArray -> JsonArray(map { it.redacted() })
        else -> this
    }

private fun String.isSensitiveToolKey(): Boolean {
    val normalized = lowercase().replace("_", "").replace("-", "")
    return SENSITIVE_TOOL_KEY_PARTS.any(normalized::contains)
}

private val SENSITIVE_TOOL_KEY_PARTS = listOf("apikey", "token", "secret", "password", "authorization", "cookie")
internal const val TOOL_REDACTED_VALUE = "[redacted]"

private val toolDisplayJson = Json { prettyPrint = true }

/** Rows persisted by older builds end with a debug "Active skills: …" line; it is not shown. */
internal fun String.withoutLegacyActiveSkillsSuffix(): String {
    val index = lastIndexOf(LEGACY_ACTIVE_SKILLS_MARKER)
    return if (index >= 0 && indexOf('\n', index + LEGACY_ACTIVE_SKILLS_MARKER.length) < 0) {
        substring(0, index).trimEnd()
    } else {
        this
    }
}

private const val LEGACY_ACTIVE_SKILLS_MARKER = "\n\nActive skills: "

/**
 * Models echo a generated image as `![alt](attachment:<id>)`, which no renderer can show;
 * the image tile already does, so the reference is dropped (a plain link keeps its label).
 */
internal fun String.withoutAttachmentLinks(): String {
    if (!contains("](attachment:", ignoreCase = true)) return this
    return ATTACHMENT_LINK.replace(this) { match -> if (match.value.startsWith("!")) "" else match.groupValues[1] }.trim()
}

private val ATTACHMENT_LINK = Regex("""!?\[([^\]\n]*)]\(attachment:[^)\n]*\)""", RegexOption.IGNORE_CASE)
