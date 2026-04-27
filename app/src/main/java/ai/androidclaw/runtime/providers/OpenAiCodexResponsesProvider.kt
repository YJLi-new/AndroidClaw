package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderOAuthCredential
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.runtime.tools.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Clock

class OpenAiCodexResponsesProvider(
    private val settingsDataStore: SettingsDataStore,
    private val providerSecretStore: ProviderSecretStore,
    private val oAuthClient: OpenAiCodexOAuthClient,
    private val baseHttpClient: OkHttpClient,
    private val json: Json,
    private val clock: Clock,
) : ModelProvider {
    override val id: String = ProviderType.OpenAiCodex.providerId
    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            supportsStreamingText = true,
            supportsStreamingToolCalls = true,
        )

    override suspend fun generate(request: ModelRequest): ModelResponse {
        var completedResponse: ModelResponse? = null
        streamGenerate(request).collect { event ->
            if (event is ModelStreamEvent.Completed) {
                completedResponse = event.response
            }
        }
        return completedResponse ?: throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "Provider stream ended without a final response.",
        )
    }

    override fun streamGenerate(request: ModelRequest): Flow<ModelStreamEvent> =
        streamProviderEvents(
            buildContext = {
                val config = resolveConfig()
                val functionNames = ProviderFunctionNameMap.from(request.toolDescriptors)
                val payload =
                    buildResponsesPayload(
                        request = request,
                        endpointSettings = config.endpointSettings,
                        functionNames = functionNames,
                    )
                val httpRequest =
                    buildHttpRequest(
                        url = config.url,
                        accessToken = config.credential.accessToken,
                        requestId = request.requestId,
                        payload = payload,
                    )
                val accumulator =
                    OpenAiCodexResponsesAccumulator(
                        json = json,
                        functionNames = functionNames,
                    )
                ProviderStreamContext(
                    endpointSettings = config.endpointSettings,
                    httpClient = config.httpClient,
                    request = httpRequest,
                    streamStarted = accumulator::hasSeenEvent,
                    canCompleteWithoutTerminalSignal = accumulator::canCompleteWithoutTerminalSignal,
                    buildResponse = accumulator::buildResponse,
                    handleDataEvent = { data, onEvent, onCompleted ->
                        handleOpenAiCodexResponsesSseData(
                            data = data,
                            accumulator = accumulator,
                            onEvent = onEvent,
                            onCompleted = onCompleted,
                        )
                    },
                )
            },
            mapHttpFailure = ::mapFailure,
        )

    private suspend fun resolveConfig(): ResolvedOAuthRequestConfig {
        val settings = settingsDataStore.settings.first()
        val endpointSettings = settings.endpointSettings(ProviderType.OpenAiCodex)
        if (endpointSettings.baseUrl.isBlank()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Configuration,
                userMessage = "Provider base URL is required.",
            )
        }
        if (endpointSettings.modelId.isBlank()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Configuration,
                userMessage = "Provider model ID is required.",
            )
        }
        val credential =
            providerSecretStore.readOAuthCredential(ProviderType.OpenAiCodex)
                ?: throw ModelProviderException(
                    kind = ModelProviderFailureKind.Configuration,
                    userMessage = "OpenAI Codex sign-in is required.",
                )
        val refreshedCredential = refreshIfNeeded(credential)
        val url = buildResponsesUrl(endpointSettings.baseUrl)
            ?: throw invalidEndpointFailure(endpointSettings.baseUrl)

        return ResolvedOAuthRequestConfig(
            endpointSettings = endpointSettings,
            credential = refreshedCredential,
            url = url,
            httpClient = baseHttpClient.withProviderTimeouts(endpointSettings),
        )
    }

    private suspend fun refreshIfNeeded(credential: ProviderOAuthCredential): ProviderOAuthCredential {
        if (credential.expiresAtEpochMillis - clock.millis() > TOKEN_REFRESH_SKEW_MILLIS) {
            return credential
        }
        val refreshed =
            try {
                oAuthClient.refreshCredential(credential)
            } catch (error: ModelProviderException) {
                throw error
            } catch (error: Exception) {
                throw ModelProviderException(
                    kind = ModelProviderFailureKind.Authentication,
                    userMessage = "OpenAI Codex OAuth refresh failed. Sign in again.",
                    details = error.message,
                    cause = error,
                )
            }
        providerSecretStore.writeOAuthCredential(ProviderType.OpenAiCodex, refreshed)
        return refreshed
    }

    private fun buildResponsesPayload(
        request: ModelRequest,
        endpointSettings: ai.androidclaw.data.ProviderEndpointSettings,
        functionNames: ProviderFunctionNameMap,
    ): JsonObject =
        buildJsonObject {
            put("model", endpointSettings.modelId)
            put("stream", true)
            put("store", false)
            request.systemPrompt
                .takeIf { it.isNotBlank() }
                ?.let { put("instructions", it) }
            put("input", buildResponsesInput(request.messageHistory, functionNames))
            request.sessionId
                .takeIf { it.isNotBlank() }
                ?.let { put("prompt_cache_key", it) }
            request.toolDescriptors
                .takeIf { it.isNotEmpty() }
                ?.let { descriptors ->
                    put(
                        "tools",
                        buildJsonArray {
                            descriptors.forEach { descriptor ->
                                add(
                                    buildJsonObject {
                                        put("type", "function")
                                        put("name", functionNames.toProviderName(descriptor.name))
                                        put("description", descriptor.description)
                                        put("parameters", descriptor.inputSchema)
                                    },
                                )
                            }
                        },
                    )
                }
        }

    private fun buildResponsesInput(
        messageHistory: List<ModelMessage>,
        functionNames: ProviderFunctionNameMap,
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
                        if (message.content.isNotBlank()) {
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
                                    splitId.itemId?.let { put("id", it) }
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

    private fun kotlinx.serialization.json.JsonArrayBuilder.addInputMessage(
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

    private fun buildHttpRequest(
        url: HttpUrl,
        accessToken: String,
        requestId: String?,
        payload: JsonObject,
    ): Request {
        val body =
            payload
                .toString()
                .toRequestBody(PROVIDER_JSON_MEDIA_TYPE)
        return Request
            .Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply {
                requestId?.let { header("X-Request-Id", it) }
            }.post(body)
            .build()
    }

    private fun buildResponsesUrl(baseUrl: String): HttpUrl? {
        val parsed = baseUrl.trim().toHttpUrlOrNull() ?: return null
        val canonicalBase =
            if (parsed.host == "chatgpt.com") {
                parsed.newBuilder()
                    .encodedPath("/backend-api/codex")
                    .build()
            } else {
                parsed
            }
        val segments = canonicalBase.pathSegments.filter { it.isNotBlank() }
        return if (segments.lastOrNull() == "responses") {
            canonicalBase
        } else {
            canonicalBase
                .newBuilder()
                .addPathSegment("responses")
                .build()
        }
    }

    private fun mapFailure(
        statusCode: Int,
        rawBody: String,
    ): ModelProviderException {
        val errorMessage =
            runCatching {
                json.parseToJsonElement(rawBody).jsonObject["error"]?.jsonObject?.stringValue("message")
            }.getOrNull().orEmpty()

        return when (statusCode) {
            401, 403 ->
                ModelProviderException(
                    kind = ModelProviderFailureKind.Authentication,
                    userMessage = "OpenAI Codex authentication failed. Sign in again.",
                    details = errorMessage.ifBlank { rawBody.take(MAX_PROVIDER_ERROR_BODY_CHARS) },
                )

            else ->
                ModelProviderException(
                    kind = ModelProviderFailureKind.Server,
                    userMessage = "OpenAI Codex request failed with HTTP $statusCode.",
                    details = errorMessage.ifBlank { rawBody.take(MAX_PROVIDER_ERROR_BODY_CHARS) },
                )
        }
    }

    private data class ResolvedOAuthRequestConfig(
        val endpointSettings: ai.androidclaw.data.ProviderEndpointSettings,
        val credential: ProviderOAuthCredential,
        val url: HttpUrl,
        val httpClient: OkHttpClient,
    )

    private companion object {
        const val TOKEN_REFRESH_SKEW_MILLIS = 60_000L
    }
}

private class OpenAiCodexResponsesAccumulator(
    private val json: Json,
    private val functionNames: ProviderFunctionNameMap,
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
                    userMessage = "OpenAI Codex returned malformed SSE JSON.",
                    details = rawEvent.take(MAX_PROVIDER_ERROR_BODY_CHARS),
                    cause = error,
                )
            } catch (error: IllegalArgumentException) {
                throw ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage = "OpenAI Codex returned malformed SSE JSON.",
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
                        "OpenAI Codex stream error: ${
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
                        userMessage = "OpenAI Codex SSE event did not include a type.",
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
                userMessage = "OpenAI Codex stream ended without any data.",
            )
        }
        val resolvedToolCalls =
            toolCalls.values.map { accumulator ->
                ProviderToolCall(
                    id =
                        ResponsesToolCallId(
                            callId = accumulator.callId.ifBlank { accumulator.itemId },
                            itemId = accumulator.itemId.takeIf { it.isNotBlank() },
                        ).storageId(),
                    name =
                        functionNames.toAndroidName(
                            accumulator.name.takeIf { it.isNotBlank() } ?: throw ModelProviderException(
                                kind = ModelProviderFailureKind.Response,
                                userMessage = "OpenAI Codex returned a tool call without a name.",
                            ),
                        ),
                    argumentsJson = parseToolArguments(accumulator.arguments),
                )
            }
        val assistantMessage = assistantText.toString()
        if (assistantMessage.isBlank() && resolvedToolCalls.isEmpty()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "OpenAI Codex stream ended without an assistant message.",
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
                val callId = item.stringValue("call_id").orEmpty()
                val itemId = item.stringValue("id").orEmpty()
                val key = toolCallKey(callId, itemId)
                currentToolCallKey = key
                val accumulator =
                    toolCalls.getOrPut(key) {
                        ToolCallAccumulator(
                            callId = callId,
                            itemId = itemId,
                        )
                    }
                item.stringValue("name")?.let { accumulator.name = it }
                item.stringValue("arguments")?.let { accumulator.arguments = it }
                listOf(
                    ModelStreamEvent.ToolCallDelta(
                        index = toolCalls.keys.indexOf(key),
                        idPart = ResponsesToolCallId(callId, itemId.takeIf { it.isNotBlank() }).storageId(),
                        namePart = accumulator.name.takeIf { it.isNotBlank() }?.let(functionNames::toAndroidName),
                    ),
                )
            }

            else -> emptyList()
        }
    }

    private fun applyTextDelta(event: JsonObject): List<ModelStreamEvent> {
        val delta = event.stringValue("delta") ?: return emptyList()
        assistantText.append(delta)
        return listOf(ModelStreamEvent.TextDelta(delta))
    }

    private fun applyToolArgumentsDelta(event: JsonObject): List<ModelStreamEvent> {
        val key = currentToolCallKey ?: return emptyList()
        val delta = event.stringValue("delta") ?: return emptyList()
        val accumulator = toolCalls[key] ?: return emptyList()
        accumulator.arguments += delta
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
                    assistantText.append(extractOutputText(item["content"]?.jsonArrayOrNull()))
                }
                emptyList()
            }

            "function_call" -> {
                val callId = item.stringValue("call_id").orEmpty()
                val itemId = item.stringValue("id").orEmpty()
                val key = toolCallKey(callId, itemId)
                val accumulator =
                    toolCalls.getOrPut(key) {
                        ToolCallAccumulator(
                            callId = callId,
                            itemId = itemId,
                        )
                    }
                item.stringValue("name")?.let { accumulator.name = it }
                item.stringValue("arguments")?.let { accumulator.arguments = it }
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
                "OpenAI Codex response failed: ${
                    error?.stringValue("message")
                        ?: response?.get("incomplete_details")?.jsonObjectOrNull()?.stringValue("reason")
                        ?: "unknown"
                }",
            details = event.toString().take(MAX_PROVIDER_ERROR_BODY_CHARS),
        )
    }

    private fun parseToolArguments(arguments: String): JsonObject =
        try {
            if (arguments.isBlank()) {
                buildJsonObject {}
            } else {
                json.parseToJsonElement(arguments).jsonObject
            }
        } catch (error: Exception) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "OpenAI Codex returned malformed tool arguments.",
                details = arguments.take(MAX_PROVIDER_ERROR_BODY_CHARS),
                cause = error,
            )
        }

    private fun toolCallKey(
        callId: String,
        itemId: String,
    ): String = "$callId|$itemId"

    private data class ToolCallAccumulator(
        val callId: String,
        val itemId: String,
        var name: String = "",
        var arguments: String = "",
    )
}

private data class ResponsesToolCallId(
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

private class ProviderFunctionNameMap private constructor(
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

private fun handleOpenAiCodexResponsesSseData(
    data: String,
    accumulator: OpenAiCodexResponsesAccumulator,
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

private fun extractOutputText(content: JsonArray?): String =
    content
        .orEmpty()
        .mapNotNull { item ->
            val block = item.jsonObjectOrNull() ?: return@mapNotNull null
            when (block.stringValue("type")) {
                "output_text" -> block.stringValue("text")
                "refusal" -> block.stringValue("refusal")
                else -> null
            }
        }.joinToString("")

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

private fun JsonObject.intValue(name: String): Int? =
    this[name]
        ?.jsonPrimitiveOrNull()
        ?.intOrNull

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

private const val MAX_PROVIDER_FUNCTION_NAME_LENGTH = 64
