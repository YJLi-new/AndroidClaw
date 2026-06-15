package ai.androidclaw.runtime.providers

import kotlinx.serialization.json.JsonObject

internal const val MAX_PROVIDER_ASSISTANT_TEXT_CHARS = 40_000
internal const val MAX_PROVIDER_TOOL_CALLS = 16
internal const val MAX_PROVIDER_TOOL_CALL_ID_CHARS = 256
internal const val MAX_PROVIDER_TOOL_CALL_NAME_CHARS = 256
internal const val MAX_PROVIDER_TOOL_ARGUMENT_CHARS = 50_000

internal fun String.toBoundedProviderAssistantText(): String = take(MAX_PROVIDER_ASSISTANT_TEXT_CHARS)

internal fun String.toBoundedProviderToolCallId(): String = take(MAX_PROVIDER_TOOL_CALL_ID_CHARS)

internal fun String.toBoundedProviderToolCallName(): String = take(MAX_PROVIDER_TOOL_CALL_NAME_CHARS)

internal fun StringBuilder.appendBoundedProviderAssistantText(text: String): String {
    val remainingChars = MAX_PROVIDER_ASSISTANT_TEXT_CHARS - length
    if (remainingChars <= 0 || text.isEmpty()) {
        return ""
    }
    val boundedText = text.take(remainingChars)
    append(boundedText)
    return boundedText
}

internal fun StringBuilder.appendBoundedProviderToolCallId(text: String): String =
    appendBoundedProviderReferenceText(text, maxChars = MAX_PROVIDER_TOOL_CALL_ID_CHARS)

internal fun StringBuilder.appendBoundedProviderToolCallName(text: String): String =
    appendBoundedProviderReferenceText(text, maxChars = MAX_PROVIDER_TOOL_CALL_NAME_CHARS)

private fun StringBuilder.appendBoundedProviderReferenceText(
    text: String,
    maxChars: Int,
): String {
    val remainingChars = maxChars - length
    if (remainingChars <= 0 || text.isEmpty()) {
        return ""
    }
    val boundedText = text.take(remainingChars)
    append(boundedText)
    return boundedText
}

internal fun StringBuilder.appendProviderToolArguments(
    text: String,
    providerName: String,
) {
    if (text.isEmpty()) {
        return
    }
    if (length + text.length > MAX_PROVIDER_TOOL_ARGUMENT_CHARS) {
        throw oversizedProviderToolArguments(providerName = providerName, details = text)
    }
    append(text)
}

internal fun String.requireProviderToolArgumentsWithinLimit(providerName: String): String {
    if (length > MAX_PROVIDER_TOOL_ARGUMENT_CHARS) {
        throw oversizedProviderToolArguments(providerName = providerName, details = this)
    }
    return this
}

internal fun JsonObject.requireProviderToolArgumentsWithinLimit(providerName: String): JsonObject {
    val serialized = toString()
    if (serialized.length > MAX_PROVIDER_TOOL_ARGUMENT_CHARS) {
        throw oversizedProviderToolArguments(providerName = providerName, details = serialized)
    }
    return this
}

internal fun <T> List<T>.requireProviderToolCallLimit(providerName: String): List<T> {
    if (size > MAX_PROVIDER_TOOL_CALLS) {
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "$providerName returned too many tool calls.",
            details = "toolCalls=$size max=$MAX_PROVIDER_TOOL_CALLS",
        )
    }
    return this
}

internal fun requireProviderToolCallCapacity(
    currentSize: Int,
    isNewToolCall: Boolean,
    providerName: String,
) {
    if (isNewToolCall && currentSize >= MAX_PROVIDER_TOOL_CALLS) {
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "$providerName returned too many tool calls.",
            details = "toolCalls=${currentSize + 1} max=$MAX_PROVIDER_TOOL_CALLS",
        )
    }
}

private fun oversizedProviderToolArguments(
    providerName: String,
    details: String,
): ModelProviderException =
    ModelProviderException(
        kind = ModelProviderFailureKind.Response,
        userMessage = "$providerName returned oversized tool arguments.",
        details = details.take(MAX_PROVIDER_ERROR_BODY_CHARS),
    )
