package ai.androidclaw.runtime.providers

import ai.androidclaw.runtime.tools.ToolDescriptor
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.Buffer

// Shared OpenAI Responses API support: request input building and response/SSE parsing.
// Used by the OpenAI Codex (ChatGPT OAuth) provider and by OpenAiCompatibleProvider for
// api.openai.com, whose current models reject function tools on chat/completions.

/**
 * Converts provider-neutral history into Responses `input` items. [includeItemIds] replays the
 * server's output item ids (Codex backend); the public API with `store=false` gets plain items.
 */
internal fun buildResponsesInput(
    messageHistory: List<ModelMessage>,
    functionNames: ProviderFunctionNameMap,
    json: Json,
    includeItemIds: Boolean = true,
): JsonArray =
    buildJsonArray {
        messageHistory.forEachIndexed { index, message ->
            when (message.role) {
                ModelMessageRole.System ->
                    addInputMessage(
                        role = "system",
                        text = message.content,
                    )

                ModelMessageRole.User ->
                    addInputMessage(
                        role = "user",
                        text = message.content,
                    )

                ModelMessageRole.Assistant -> {
                    if (message.content.isNotBlank() && !includeItemIds) {
                        add(
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", message.content)
                            },
                        )
                    } else if (message.content.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("type", "message")
                                put("role", "assistant")
                                put("status", "completed")
                                put("id", "msg_$index")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "output_text")
                                                put("text", message.content)
                                                put("annotations", buildJsonArray {})
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }
                    message.toolCalls.forEach { toolCall ->
                        val splitId = ResponsesToolCallId.parse(toolCall.id)
                        add(
                            buildJsonObject {
                                put("type", "function_call")
                                put("call_id", splitId.callId)
                                if (includeItemIds) {
                                    splitId.itemId?.let { put("id", it) }
                                }
                                put("name", functionNames.toProviderName(toolCall.name))
                                put(
                                    "arguments",
                                    json.encodeToString(JsonObject.serializer(), toolCall.argumentsJson),
                                )
                            },
                        )
                    }
                }

                ModelMessageRole.Tool ->
                    add(
                        buildJsonObject {
                            put("type", "function_call_output")
                            put(
                                "call_id",
                                ResponsesToolCallId
                                    .parse(
                                        message.toolCallId ?: throw ModelProviderException(
                                            kind = ModelProviderFailureKind.Response,
                                            userMessage = "Provider request included a tool result without a tool call id.",
                                        ),
                                    ).callId,
                            )
                            put("output", message.content)
                        },
                    )
            }
        }
    }

private fun JsonArrayBuilder.addInputMessage(
    role: String,
    text: String,
) {
    if (text.isBlank()) {
        return
    }
    add(
        buildJsonObject {
            put("role", role)
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "input_text")
                            put("text", text)
                        },
                    )
                },
            )
        },
    )
}

internal class ResponsesAccumulator(
    private val json: Json,
    private val functionNames: ProviderFunctionNameMap,
    private val providerName: String = "OpenAI Codex",
) {
    private val assistantText = StringBuilder()
    private val toolCalls = linkedMapOf<String, ToolCallAccumulator>()
    private var currentToolCallKey: String? = null
    private var providerRequestId: String? = null
    private var modelId: String? = null
    private var finishReason: String? = null
    private var usage: ProviderUsage? = null
    private var sawEvent = false
    private var completed = false

    fun applyEvent(rawEvent: String): List<ModelStreamEvent> {
        val event =
            try {
                json.parseToJsonElement(rawEvent).jsonObject
            } catch (error: SerializationException) {
                throw ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage = "$providerName returned malformed SSE JSON.",
                    details = rawEvent.take(MAX_PROVIDER_ERROR_BODY_CHARS),
                    cause = error,
                )
            } catch (error: IllegalArgumentException) {
                throw ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage = "$providerName returned malformed SSE JSON.",
                    details = rawEvent.take(MAX_PROVIDER_ERROR_BODY_CHARS),
                    cause = error,
                )
            }
        sawEvent = true
        return when (val type = event.stringValue("type")) {
            "response.created" -> {
                event["response"]?.jsonObjectOrNull()?.let { response ->
                    providerRequestId = response.stringValue("id") ?: providerRequestId
                    modelId = response.stringValue("model") ?: modelId
                }
                emptyList()
            }

            "response.output_item.added" -> applyOutputItemAdded(event)
            "response.output_text.delta", "response.refusal.delta" -> applyTextDelta(event)
            "response.function_call_arguments.delta" -> applyToolArgumentsDelta(event)
            "response.output_item.done" -> applyOutputItemDone(event)
            "response.completed" -> {
                applyCompleted(event)
                emptyList()
            }

            "response.failed" -> throw failedResponse(event)
            "error" ->
                throw ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage =
                        "$providerName stream error: ${
                            event.stringValue("message")
                                ?: event.stringValue("code")
                                ?: "unknown"
                        }",
                    details = rawEvent.take(MAX_PROVIDER_ERROR_BODY_CHARS),
                )

            else -> {
                if (type.isNullOrBlank()) {
                    throw ModelProviderException(
                        kind = ModelProviderFailureKind.Response,
                        userMessage = "$providerName SSE event did not include a type.",
                        details = rawEvent.take(MAX_PROVIDER_ERROR_BODY_CHARS),
                    )
                }
                emptyList()
            }
        }
    }

    fun buildResponse(): ModelResponse {
        if (!sawEvent) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "$providerName stream ended without any data.",
            )
        }
        val resolvedToolCalls =
            toolCalls.values.map { accumulator ->
                val name =
                    accumulator.name.takeIf { it.isNotBlank() } ?: throw ModelProviderException(
                        kind = ModelProviderFailureKind.Response,
                        userMessage = "$providerName returned a tool call without a name.",
                    )
                ProviderToolCall(
                    id =
                        ResponsesToolCallId(
                            callId = accumulator.callId.ifBlank { accumulator.itemId },
                            itemId = accumulator.itemId.takeIf { it.isNotBlank() },
                        ).storageId(),
                    name = functionNames.toAndroidName(name).toBoundedProviderToolCallName(),
                    argumentsJson = parseToolArguments(accumulator.arguments.toString()),
                )
            }
        val assistantMessage = assistantText.toString().toBoundedProviderAssistantText()
        if (assistantMessage.isBlank() && resolvedToolCalls.isEmpty()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "$providerName stream ended without an assistant message.",
            )
        }
        val normalizedFinishReason =
            when {
                resolvedToolCalls.isNotEmpty() -> "tool_use"
                finishReason.isNullOrBlank() -> "stop"
                else -> finishReason.orEmpty()
            }
        return ModelResponse(
            text = assistantMessage,
            providerRequestId = providerRequestId,
            finishReason = normalizedFinishReason,
            toolCalls = resolvedToolCalls,
            modelId = modelId,
            usage = usage,
        )
    }

    fun hasSeenEvent(): Boolean = sawEvent

    fun canCompleteWithoutTerminalSignal(): Boolean = completed

    private fun applyOutputItemAdded(event: JsonObject): List<ModelStreamEvent> {
        val item = event["item"]?.jsonObjectOrNull() ?: return emptyList()
        return when (item.stringValue("type")) {
            "message" -> emptyList()
            "function_call" -> {
                val callId = item.stringValue("call_id").orEmpty().toBoundedProviderToolCallId()
                val itemId = item.stringValue("id").orEmpty().toBoundedProviderToolCallId()
                val key = toolCallKey(callId, itemId)
                currentToolCallKey = key
                requireProviderToolCallCapacity(
                    currentSize = toolCalls.size,
                    isNewToolCall = !toolCalls.containsKey(key),
                    providerName = providerName,
                )
                val accumulator =
                    toolCalls.getOrPut(key) {
                        ToolCallAccumulator(
                            callId = callId,
                            itemId = itemId,
                        )
                    }
                item.stringValue("name")?.let { accumulator.name = it.toBoundedProviderToolCallName() }
                item.stringValue("arguments")?.let {
                    accumulator.arguments.clear()
                    accumulator.arguments.appendProviderToolArguments(
                        text = it,
                        providerName = providerName,
                    )
                }
                listOf(
                    ModelStreamEvent.ToolCallDelta(
                        index = toolCalls.keys.indexOf(key),
                        idPart = ResponsesToolCallId(callId, itemId.takeIf { it.isNotBlank() }).storageId(),
                        namePart =
                            accumulator.name
                                .takeIf { it.isNotBlank() }
                                ?.let(functionNames::toAndroidName)
                                ?.toBoundedProviderToolCallName(),
                    ),
                )
            }

            else -> emptyList()
        }
    }

    private fun applyTextDelta(event: JsonObject): List<ModelStreamEvent> {
        val delta = event.rawStringValue("delta") ?: return emptyList()
        val boundedDelta = assistantText.appendBoundedProviderAssistantText(delta)
        if (boundedDelta.isEmpty()) {
            return emptyList()
        }
        return listOf(ModelStreamEvent.TextDelta(boundedDelta))
    }

    private fun applyToolArgumentsDelta(event: JsonObject): List<ModelStreamEvent> {
        val key = currentToolCallKey ?: return emptyList()
        val delta = event.rawStringValue("delta") ?: return emptyList()
        val accumulator = toolCalls[key] ?: return emptyList()
        accumulator.arguments.appendProviderToolArguments(
            text = delta,
            providerName = providerName,
        )
        return listOf(
            ModelStreamEvent.ToolCallDelta(
                index = toolCalls.keys.indexOf(key),
                argumentsPart = delta,
            ),
        )
    }

    private fun applyOutputItemDone(event: JsonObject): List<ModelStreamEvent> {
        val item = event["item"]?.jsonObjectOrNull() ?: return emptyList()
        return when (item.stringValue("type")) {
            "message" -> {
                if (assistantText.isBlank()) {
                    assistantText.appendBoundedProviderAssistantText(extractOutputText(item["content"]?.jsonArrayOrNull()))
                }
                emptyList()
            }

            "function_call" -> {
                val callId = item.stringValue("call_id").orEmpty().toBoundedProviderToolCallId()
                val itemId = item.stringValue("id").orEmpty().toBoundedProviderToolCallId()
                val key = toolCallKey(callId, itemId)
                requireProviderToolCallCapacity(
                    currentSize = toolCalls.size,
                    isNewToolCall = !toolCalls.containsKey(key),
                    providerName = providerName,
                )
                val accumulator =
                    toolCalls.getOrPut(key) {
                        ToolCallAccumulator(
                            callId = callId,
                            itemId = itemId,
                        )
                    }
                item.stringValue("name")?.let { accumulator.name = it.toBoundedProviderToolCallName() }
                item.stringValue("arguments")?.let {
                    accumulator.arguments.clear()
                    accumulator.arguments.appendProviderToolArguments(
                        text = it,
                        providerName = providerName,
                    )
                }
                currentToolCallKey = null
                emptyList()
            }

            else -> emptyList()
        }
    }

    private fun applyCompleted(event: JsonObject) {
        val response = event["response"]?.jsonObjectOrNull()
        providerRequestId = response?.stringValue("id") ?: providerRequestId
        modelId = response?.stringValue("model") ?: modelId
        finishReason = mapResponsesStopReason(response?.stringValue("status"))
        usage = response?.get("usage")?.jsonObjectOrNull()?.toProviderUsage() ?: usage
        completed = true
    }

    private fun failedResponse(event: JsonObject): ModelProviderException {
        val response = event["response"]?.jsonObjectOrNull()
        val error = response?.get("error")?.jsonObjectOrNull()
        return ModelProviderException(
            kind = ModelProviderFailureKind.Server,
            userMessage =
                "$providerName response failed: ${
                    error?.stringValue("message")
                        ?: response?.get("incomplete_details")?.jsonObjectOrNull()?.stringValue("reason")
                        ?: "unknown"
                }",
            details = event.toString().take(MAX_PROVIDER_ERROR_BODY_CHARS),
        )
    }

    private fun parseToolArguments(arguments: String): JsonObject =
        arguments.requireProviderToolArgumentsWithinLimit(providerName = providerName).let { boundedArguments ->
            try {
                if (boundedArguments.isBlank()) {
                    buildJsonObject {}
                } else {
                    json.parseToJsonElement(boundedArguments).jsonObject
                }
            } catch (error: Exception) {
                throw ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage = "$providerName returned malformed tool arguments.",
                    details = boundedArguments.take(MAX_PROVIDER_ERROR_BODY_CHARS),
                    cause = error,
                )
            }
        }

    private fun toolCallKey(
        callId: String,
        itemId: String,
    ): String = "$callId|$itemId"

    private data class ToolCallAccumulator(
        val callId: String,
        val itemId: String,
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )
}

internal data class ResponsesToolCallId(
    val callId: String,
    val itemId: String? = null,
) {
    fun storageId(): String =
        if (itemId.isNullOrBlank()) {
            callId
        } else {
            "$callId|$itemId"
        }

    companion object {
        fun parse(value: String): ResponsesToolCallId {
            val parts = value.split("|", limit = 2)
            return ResponsesToolCallId(
                callId = parts.firstOrNull().orEmpty(),
                itemId = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
            )
        }
    }
}

internal class ProviderFunctionNameMap private constructor(
    private val androidToProvider: Map<String, String>,
) {
    private val providerToAndroid = androidToProvider.entries.associate { (android, provider) -> provider to android }

    fun toProviderName(androidName: String): String = androidToProvider[androidName] ?: sanitizeProviderFunctionName(androidName)

    fun toAndroidName(providerName: String): String = providerToAndroid[providerName] ?: providerName

    companion object {
        fun from(descriptors: List<ToolDescriptor>): ProviderFunctionNameMap {
            val usedNames = mutableSetOf<String>()
            val mapping =
                descriptors.associate { descriptor ->
                    val base = sanitizeProviderFunctionName(descriptor.name)
                    var candidate = base
                    var suffix = 2
                    while (!usedNames.add(candidate)) {
                        val suffixText = "_$suffix"
                        candidate = base.take((MAX_PROVIDER_FUNCTION_NAME_LENGTH - suffixText.length).coerceAtLeast(1)) + suffixText
                        suffix += 1
                    }
                    descriptor.name to candidate
                }
            return ProviderFunctionNameMap(mapping)
        }

        private fun sanitizeProviderFunctionName(name: String): String {
            val sanitized =
                name
                    .map { char ->
                        if (char.isLetterOrDigit() || char == '_' || char == '-') {
                            char
                        } else {
                            '_'
                        }
                    }.joinToString("")
                    .trim('_', '-')
                    .ifBlank { "tool" }
            return sanitized.take(MAX_PROVIDER_FUNCTION_NAME_LENGTH)
        }
    }
}

internal fun handleResponsesSseData(
    data: String,
    accumulator: ResponsesAccumulator,
    onEvent: (ModelStreamEvent) -> Unit,
    onCompleted: (ModelResponse) -> Unit,
): Boolean {
    if (data.isBlank()) {
        return false
    }
    if (data == PROVIDER_SSE_DONE_SENTINEL) {
        onCompleted(accumulator.buildResponse())
        return true
    }
    accumulator.applyEvent(data).forEach(onEvent)
    if (accumulator.canCompleteWithoutTerminalSignal()) {
        onCompleted(accumulator.buildResponse())
        return true
    }
    return false
}

internal fun parseResponsesNonEventStreamResponse(
    rawBody: String,
    json: Json,
    functionNames: ProviderFunctionNameMap,
    providerName: String = "OpenAI Codex",
): ModelResponse? {
    if (rawBody.isBlank()) {
        return null
    }
    if (rawBody.lineSequence().any { it.startsWith("data:") }) {
        val accumulator =
            ResponsesAccumulator(
                json = json,
                functionNames = functionNames,
                providerName = providerName,
            )
        var completedResponse: ModelResponse? = null
        readSseDataEvents(Buffer().writeUtf8(rawBody)) { data ->
            handleResponsesSseData(
                data = data,
                accumulator = accumulator,
                onEvent = {},
                onCompleted = { completedResponse = it },
            )
        }
        return completedResponse
            ?: if (accumulator.hasSeenEvent()) {
                accumulator.buildResponse()
            } else {
                null
            }
    }
    val response =
        runCatching {
            val payload = json.parseToJsonElement(rawBody).jsonObject
            payload["response"]?.jsonObjectOrNull() ?: payload
        }.getOrNull() ?: return null
    return parseResponsesResponseObject(
        response = response,
        json = json,
        functionNames = functionNames,
        providerName = providerName,
    )
}

internal fun parseResponsesResponseObject(
    response: JsonObject,
    json: Json,
    functionNames: ProviderFunctionNameMap,
    providerName: String = "OpenAI Codex",
): ModelResponse? {
    val output = response["output"]?.jsonArrayOrNull().orEmpty()
    val text =
        output
            .mapNotNull { item ->
                val block = item.jsonObjectOrNull() ?: return@mapNotNull null
                when (block.stringValue("type")) {
                    "message" -> extractOutputText(block["content"]?.jsonArrayOrNull())
                    "output_text" -> block.stringValue("text")
                    "refusal" -> block.stringValue("refusal")
                    else -> null
                }
            }.joinToString("")
            .toBoundedProviderAssistantText()
    val toolCalls =
        output
            .mapNotNull { item ->
                val block = item.jsonObjectOrNull() ?: return@mapNotNull null
                if (block.stringValue("type") != "function_call") {
                    return@mapNotNull null
                }
                val callId = block.stringValue("call_id").orEmpty()
                val itemId = block.stringValue("id").orEmpty()
                val name = block.stringValue("name") ?: return@mapNotNull null
                ProviderToolCall(
                    id =
                        ResponsesToolCallId(
                            callId = callId.ifBlank { itemId }.toBoundedProviderToolCallId(),
                            itemId = itemId.takeIf { it.isNotBlank() }?.toBoundedProviderToolCallId(),
                        ).storageId(),
                    name = functionNames.toAndroidName(name).toBoundedProviderToolCallName(),
                    argumentsJson =
                        parseResponsesToolArguments(
                            json = json,
                            arguments = block.stringValue("arguments").orEmpty(),
                            providerName = providerName,
                        ),
                )
            }.requireProviderToolCallLimit(providerName = providerName)
    if (text.isBlank() && toolCalls.isEmpty()) {
        return null
    }
    return ModelResponse(
        text = text,
        providerRequestId = response.stringValue("id"),
        finishReason =
            if (toolCalls.isNotEmpty()) {
                "tool_use"
            } else {
                mapResponsesStopReason(response.stringValue("status"))
            },
        toolCalls = toolCalls,
        modelId = response.stringValue("model"),
        usage = response["usage"]?.jsonObjectOrNull()?.toProviderUsage(),
    )
}

private fun parseResponsesToolArguments(
    json: Json,
    arguments: String,
    providerName: String,
): JsonObject =
    arguments.requireProviderToolArgumentsWithinLimit(providerName = providerName).let { boundedArguments ->
        try {
            if (boundedArguments.isBlank()) {
                buildJsonObject {}
            } else {
                json.parseToJsonElement(boundedArguments).jsonObject
            }
        } catch (error: Exception) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "$providerName returned malformed tool arguments.",
                details = boundedArguments.take(MAX_PROVIDER_ERROR_BODY_CHARS),
                cause = error,
            )
        }
    }

private fun extractOutputText(content: JsonArray?): String =
    buildString {
        content.orEmpty().forEach { item ->
            val block = item.jsonObjectOrNull() ?: return@forEach
            val text =
                when (block.stringValue("type")) {
                    "output_text" -> block.stringValue("text")
                    "refusal" -> block.stringValue("refusal")
                    else -> null
                }
            if (text != null) {
                appendBoundedProviderAssistantText(text)
            }
        }
    }

private fun mapResponsesStopReason(status: String?): String =
    when (status) {
        null, "", "completed" -> "stop"
        "incomplete" -> "length"
        "failed", "cancelled" -> "error"
        else -> "stop"
    }

private fun JsonObject.toProviderUsage(): ProviderUsage {
    val inputTokens = intValue("input_tokens")
    val outputTokens = intValue("output_tokens")
    val totalTokens = intValue("total_tokens")
    return ProviderUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
    )
}

private fun JsonObject.stringValue(name: String): String? =
    this[name]
        ?.jsonPrimitiveOrNull()
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }

// Stream deltas keep their whitespace: trimming them glued words together ("Hello " + "there").
private fun JsonObject.rawStringValue(name: String): String? =
    this[name]
        ?.jsonPrimitiveOrNull()
        ?.contentOrNull
        ?.takeIf { it.isNotEmpty() }

private fun JsonObject.intValue(name: String): Int? =
    this[name]
        ?.jsonPrimitiveOrNull()
        ?.intOrNull

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

private const val MAX_PROVIDER_FUNCTION_NAME_LENGTH = 64
