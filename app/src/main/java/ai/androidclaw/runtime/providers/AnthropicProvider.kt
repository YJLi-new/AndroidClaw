package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AnthropicProvider(
    private val settingsDataStore: SettingsDataStore,
    private val providerSecretStore: ProviderSecretStore,
    private val baseHttpClient: OkHttpClient,
    private val json: Json,
) : ModelProvider {
    override val id: String = ProviderType.Anthropic.providerId
    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            supportsStreamingText = true,
            supportsStreamingToolCalls = true,
        )

    override suspend fun generate(request: ModelRequest): ModelResponse {
        val config = resolveConfig()
        val payload = buildRequestPayload(request, config.endpointSettings, stream = false)
        val httpRequest =
            buildHttpRequest(
                url = config.url,
                apiKey = config.apiKey,
                requestId = request.requestId,
                payload = payload,
            )

        try {
            config.httpClient.newCall(httpRequest).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw mapFailure(response.code, rawBody)
                }
                return parseBatchResponse(rawBody)
            }
        } catch (error: SocketTimeoutException) {
            throw timeoutFailure(config.endpointSettings, error)
        } catch (error: InterruptedIOException) {
            throw timeoutFailure(config.endpointSettings, error)
        } catch (error: IOException) {
            throw mapTransportFailure(error)
        }
    }

    override fun streamGenerate(request: ModelRequest): Flow<ModelStreamEvent> =
        channelFlow {
            val config =
                try {
                    resolveConfig()
                } catch (error: Exception) {
                    close(error)
                    return@channelFlow
                }
            val payload = buildRequestPayload(request, config.endpointSettings, stream = true)
            val httpRequest =
                buildHttpRequest(
                    url = config.url,
                    apiKey = config.apiKey,
                    requestId = request.requestId,
                    payload = payload,
                )
            val accumulator = AnthropicStreamAccumulator(json)
            val completed = AtomicBoolean(false)
            val cancelledByCollector = AtomicBoolean(false)
            val streamingClient =
                config.httpClient
                    .newBuilder()
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
            val call = streamingClient.newCall(httpRequest)

            launch(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val rawBody = response.body?.string().orEmpty()
                            throw mapFailure(response.code, rawBody)
                        }

                        val body =
                            response.body ?: throw ModelProviderException(
                                kind = ModelProviderFailureKind.Response,
                                userMessage = "Provider stream ended without a response body.",
                            )
                        val contentType = body.contentType()?.toString().orEmpty()
                        if (!contentType.startsWith(EVENT_STREAM_CONTENT_TYPE_PREFIX)) {
                            throw ModelProviderException(
                                kind = ModelProviderFailureKind.Response,
                                userMessage = "Provider stream did not return an event stream.",
                                details = contentType,
                            )
                        }

                        val pendingDataLines = mutableListOf<String>()
                        val source = body.source()
                        var doneSeen = false

                        while (!doneSeen) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("event:")) {
                                continue
                            }
                            if (line.isBlank()) {
                                doneSeen =
                                    flushPendingAnthropicEvent(
                                        pendingDataLines = pendingDataLines,
                                        accumulator = accumulator,
                                        onEvent = { event: ModelStreamEvent -> trySend(event) },
                                        onCompleted = { responseValue: ModelResponse ->
                                            if (completed.compareAndSet(false, true)) {
                                                trySend(ModelStreamEvent.Completed(responseValue))
                                                close()
                                            }
                                        },
                                    )
                                continue
                            }
                            if (line.startsWith("data:")) {
                                pendingDataLines += line.removePrefix("data:").trimStart()
                            }
                        }

                        if (!doneSeen) {
                            doneSeen =
                                flushPendingAnthropicEvent(
                                    pendingDataLines = pendingDataLines,
                                    accumulator = accumulator,
                                    onEvent = { event: ModelStreamEvent -> trySend(event) },
                                    onCompleted = { responseValue: ModelResponse ->
                                        if (completed.compareAndSet(false, true)) {
                                            trySend(ModelStreamEvent.Completed(responseValue))
                                            close()
                                        }
                                    },
                                )
                        }

                        if (!doneSeen) {
                            if (!accumulator.canCompleteWithoutTerminalSignal()) {
                                throw streamInterruptedFailure(
                                    details = "Provider stream ended before a terminal event was received.",
                                )
                            }
                            if (completed.compareAndSet(false, true)) {
                                trySend(ModelStreamEvent.Completed(accumulator.buildResponse()))
                                close()
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (cancelledByCollector.get() && error is IOException) {
                        return@launch
                    }
                    if (completed.get()) {
                        return@launch
                    }
                    if (completed.compareAndSet(false, true)) {
                        close(
                            mapStreamingFailure(
                                settings = config.endpointSettings,
                                response = null,
                                rawBody = "",
                                throwable = error,
                                streamStarted = accumulator.hasSeenEvent(),
                            ),
                        )
                    }
                }
            }

            awaitClose {
                if (!completed.get()) {
                    cancelledByCollector.set(true)
                    call.cancel()
                }
            }
        }

    private suspend fun resolveConfig(): ResolvedRequestConfig {
        val settings = settingsDataStore.settings.first()
        val endpointSettings = settings.endpointSettings(ProviderType.Anthropic)
        val apiKey = providerSecretStore.readApiKey(ProviderType.Anthropic)

        validateSettings(endpointSettings, apiKey)

        val url =
            endpointSettings.baseUrl
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addPathSegment("messages")
                ?.build()
                ?: throw invalidEndpointFailure(endpointSettings.baseUrl)

        return ResolvedRequestConfig(
            endpointSettings = endpointSettings,
            apiKey = apiKey.orEmpty(),
            url = url,
            httpClient =
                baseHttpClient
                    .newBuilder()
                    .callTimeout(endpointSettings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .connectTimeout(endpointSettings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .readTimeout(endpointSettings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .writeTimeout(endpointSettings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    .build(),
        )
    }

    private fun validateSettings(
        settings: ProviderEndpointSettings,
        apiKey: String?,
    ) {
        if (settings.baseUrl.isBlank()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Configuration,
                userMessage = "Provider base URL is required.",
            )
        }
        if (settings.modelId.isBlank()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Configuration,
                userMessage = "Provider model ID is required.",
            )
        }
        if (apiKey.isNullOrBlank()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Configuration,
                userMessage = "Provider API key is required.",
            )
        }
    }

    private fun buildRequestPayload(
        request: ModelRequest,
        endpointSettings: ProviderEndpointSettings,
        stream: Boolean,
    ): AnthropicMessagesRequest =
        AnthropicMessagesRequest(
            model = endpointSettings.modelId,
            system = request.systemPrompt.takeIf { it.isNotBlank() },
            messages = buildMessages(request.messageHistory),
            tools =
                request.toolDescriptors
                    .takeIf { it.isNotEmpty() }
                    ?.map { descriptor ->
                        AnthropicToolDefinition(
                            name = descriptor.name,
                            description = descriptor.description,
                            inputSchema = descriptor.inputSchema,
                        )
                    },
            stream = stream,
            maxTokens = DEFAULT_ANTHROPIC_MAX_OUTPUT_TOKENS,
        )

    private fun buildMessages(messageHistory: List<ModelMessage>): List<AnthropicMessage> =
        messageHistory.map { message ->
            when (message.role) {
                ModelMessageRole.System ->
                    AnthropicMessage(
                        role = "user",
                        content =
                            listOf(
                                AnthropicRequestContentBlock(
                                    type = "text",
                                    text = "System message: ${message.content}",
                                ),
                            ),
                    )

                ModelMessageRole.User ->
                    AnthropicMessage(
                        role = "user",
                        content =
                            listOf(
                                AnthropicRequestContentBlock(
                                    type = "text",
                                    text = message.content,
                                ),
                            ),
                    )

                ModelMessageRole.Assistant ->
                    AnthropicMessage(
                        role = "assistant",
                        content =
                            buildList {
                                message.content
                                    .takeIf { it.isNotBlank() }
                                    ?.let { text ->
                                        add(
                                            AnthropicRequestContentBlock(
                                                type = "text",
                                                text = text,
                                            ),
                                        )
                                    }
                                message.toolCalls.forEach { toolCall ->
                                    add(
                                        AnthropicRequestContentBlock(
                                            type = "tool_use",
                                            id = toolCall.id,
                                            name = toolCall.name,
                                            input = toolCall.argumentsJson,
                                        ),
                                    )
                                }
                            },
                    )

                ModelMessageRole.Tool ->
                    AnthropicMessage(
                        role = "user",
                        content =
                            listOf(
                                AnthropicRequestContentBlock(
                                    type = "tool_result",
                                    toolUseId =
                                        message.toolCallId ?: throw ModelProviderException(
                                            kind = ModelProviderFailureKind.Response,
                                            userMessage = "Provider request included a tool result without a tool call id.",
                                        ),
                                    content = message.content,
                                ),
                            ),
                    )
            }
        }

    private fun buildHttpRequest(
        url: HttpUrl,
        apiKey: String,
        requestId: String?,
        payload: AnthropicMessagesRequest,
    ): Request {
        val body =
            json
                .encodeToString(AnthropicMessagesRequest.serializer(), payload)
                .toRequestBody(JSON_MEDIA_TYPE)
        return Request
            .Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .apply {
                requestId?.let { header("X-Request-Id", it) }
            }.post(body)
            .build()
    }

    private fun parseBatchResponse(rawBody: String): ModelResponse {
        val parsed =
            try {
                json.decodeFromString(AnthropicMessagesResponse.serializer(), rawBody)
            } catch (error: SerializationException) {
                throw ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage = "Provider returned malformed JSON.",
                    details = rawBody.take(MAX_ERROR_BODY_CHARS),
                    cause = error,
                )
            }

        val assistantText =
            parsed.content
                .filter { it.type == "text" }
                .joinToString(separator = "") { it.text.orEmpty() }
                .trim()
        val toolCalls =
            parsed.content
                .filter { it.type == "tool_use" }
                .map { block ->
                    ProviderToolCall(
                        id =
                            block.id ?: throw ModelProviderException(
                                kind = ModelProviderFailureKind.Response,
                                userMessage = "Provider returned a tool call without an id.",
                            ),
                        name =
                            block.name ?: throw ModelProviderException(
                                kind = ModelProviderFailureKind.Response,
                                userMessage = "Provider returned a tool call without a name.",
                            ),
                        argumentsJson = block.input ?: buildJsonObject {},
                    )
                }

        if (assistantText.isBlank() && toolCalls.isEmpty()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider response did not contain an assistant message.",
                details = rawBody.take(MAX_ERROR_BODY_CHARS),
            )
        }

        return ModelResponse(
            text = assistantText,
            providerRequestId = parsed.id,
            finishReason =
                when {
                    toolCalls.isNotEmpty() || parsed.stopReason == "tool_use" -> "tool_use"
                    parsed.stopReason.isNullOrBlank() -> "stop"
                    else -> parsed.stopReason
                },
            toolCalls = toolCalls,
            modelId = parsed.model,
            usage = parsed.usage?.toProviderUsage(),
        )
    }

    private fun mapFailure(
        statusCode: Int,
        rawBody: String,
    ): ModelProviderException {
        val parsedMessage =
            runCatching {
                json.decodeFromString(AnthropicErrorEnvelope.serializer(), rawBody).error?.message
            }.getOrNull().orEmpty()

        return when (statusCode) {
            401, 403 ->
                ModelProviderException(
                    kind = ModelProviderFailureKind.Authentication,
                    userMessage = "Provider authentication failed.",
                    details = parsedMessage.ifBlank { rawBody.take(MAX_ERROR_BODY_CHARS) },
                )

            else ->
                ModelProviderException(
                    kind = ModelProviderFailureKind.Server,
                    userMessage = "Provider request failed with HTTP $statusCode.",
                    details = parsedMessage.ifBlank { rawBody.take(MAX_ERROR_BODY_CHARS) },
                )
        }
    }

    private fun mapStreamingFailure(
        settings: ProviderEndpointSettings,
        response: Response?,
        rawBody: String,
        throwable: Throwable?,
        streamStarted: Boolean,
    ): ModelProviderException {
        if (throwable is ModelProviderException) {
            return throwable
        }
        if (response != null) {
            return mapFailure(response.code, rawBody)
        }
        return when (throwable) {
            is SocketTimeoutException -> timeoutFailure(settings, throwable)
            is InterruptedIOException -> timeoutFailure(settings, throwable)
            is IOException ->
                if (streamStarted) {
                    streamInterruptedFailure(
                        details = throwable.message,
                        cause = throwable,
                    )
                } else {
                    mapTransportFailure(throwable)
                }

            else ->
                ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage = "Provider streaming failed.",
                    details = throwable?.message,
                    cause = throwable,
                )
        }
    }

    private data class ResolvedRequestConfig(
        val endpointSettings: ProviderEndpointSettings,
        val apiKey: String,
        val url: HttpUrl,
        val httpClient: OkHttpClient,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val EVENT_STREAM_CONTENT_TYPE_PREFIX = "text/event-stream"
        const val MAX_ERROR_BODY_CHARS = 500
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_ANTHROPIC_MAX_OUTPUT_TOKENS = 2048
    }
}

@Serializable
private data class AnthropicMessagesRequest(
    val model: String,
    val system: String? = null,
    val messages: List<AnthropicMessage>,
    val tools: List<AnthropicToolDefinition>? = null,
    val stream: Boolean = false,
    @SerialName("max_tokens")
    val maxTokens: Int,
)

@Serializable
private data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicRequestContentBlock>,
)

@Serializable
private data class AnthropicRequestContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    @SerialName("tool_use_id")
    val toolUseId: String? = null,
    val content: String? = null,
)

@Serializable
private data class AnthropicToolDefinition(
    val name: String,
    val description: String,
    @SerialName("input_schema")
    val inputSchema: JsonObject,
)

@Serializable
private data class AnthropicMessagesResponse(
    val id: String? = null,
    val model: String? = null,
    val content: List<AnthropicOutputContentBlock> = emptyList(),
    @SerialName("stop_reason")
    val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
private data class AnthropicOutputContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
)

@Serializable
private data class AnthropicErrorEnvelope(
    val error: AnthropicErrorPayload? = null,
)

@Serializable
private data class AnthropicErrorPayload(
    val message: String? = null,
)

@Serializable
private data class AnthropicStreamEnvelope(
    val type: String,
    val index: Int? = null,
    val message: AnthropicStreamMessageStart? = null,
    @SerialName("content_block")
    val contentBlock: AnthropicStreamContentBlock? = null,
    val delta: AnthropicStreamDelta? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
private data class AnthropicStreamMessageStart(
    val id: String? = null,
    val model: String? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
private data class AnthropicStreamContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
)

@Serializable
private data class AnthropicStreamDelta(
    val type: String? = null,
    val text: String? = null,
    @SerialName("partial_json")
    val partialJson: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null,
)

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens")
    val inputTokens: Int? = null,
    @SerialName("output_tokens")
    val outputTokens: Int? = null,
)

private class AnthropicStreamAccumulator(
    private val json: Json,
) {
    private val assistantText = StringBuilder()
    private val toolCalls = linkedMapOf<Int, ToolCallAccumulator>()
    private var providerRequestId: String? = null
    private var modelId: String? = null
    private var finishReason: String? = null
    private var usage: ProviderUsage? = null
    private var sawEvent = false
    private var sawMessageStop = false

    fun applyEnvelope(rawEnvelope: String): List<ModelStreamEvent> {
        val envelope =
            try {
                json.decodeFromString(AnthropicStreamEnvelope.serializer(), rawEnvelope)
            } catch (error: SerializationException) {
                throw ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage = "Provider returned malformed SSE event.",
                    details = rawEnvelope.take(500),
                    cause = error,
                )
            }

        sawEvent = true
        return when (envelope.type) {
            "ping" -> emptyList()
            "message_start" -> {
                providerRequestId = envelope.message?.id ?: providerRequestId
                modelId = envelope.message?.model ?: modelId
                usage = mergeUsage(usage, envelope.message?.usage)
                emptyList()
            }

            "content_block_start" -> {
                when (envelope.contentBlock?.type) {
                    "tool_use" -> {
                        val index = envelope.index ?: 0
                        val accumulator = toolCalls.getOrPut(index) { ToolCallAccumulator() }
                        buildList {
                            envelope.contentBlock.id
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { idPart ->
                                    accumulator.id.append(idPart)
                                    add(ModelStreamEvent.ToolCallDelta(index = index, idPart = idPart))
                                }
                            envelope.contentBlock.name
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { namePart ->
                                    accumulator.name.append(namePart)
                                    add(ModelStreamEvent.ToolCallDelta(index = index, namePart = namePart))
                                }
                        }
                    }

                    else -> emptyList()
                }
            }

            "content_block_delta" -> {
                when (envelope.delta?.type) {
                    "text_delta" -> {
                        val textPart = envelope.delta.text.orEmpty()
                        if (textPart.isBlank()) {
                            emptyList()
                        } else {
                            assistantText.append(textPart)
                            listOf(ModelStreamEvent.TextDelta(textPart))
                        }
                    }

                    "input_json_delta" -> {
                        val index = envelope.index ?: 0
                        val accumulator = toolCalls.getOrPut(index) { ToolCallAccumulator() }
                        val argumentsPart = envelope.delta.partialJson.orEmpty()
                        if (argumentsPart.isBlank()) {
                            emptyList()
                        } else {
                            accumulator.arguments.append(argumentsPart)
                            listOf(ModelStreamEvent.ToolCallDelta(index = index, argumentsPart = argumentsPart))
                        }
                    }

                    else -> emptyList()
                }
            }

            "message_delta" -> {
                envelope.delta?.stopReason?.let { finishReason = it }
                usage = mergeUsage(usage, envelope.usage)
                emptyList()
            }

            "content_block_stop" -> emptyList()
            "message_stop" -> {
                sawMessageStop = true
                emptyList()
            }
            else -> emptyList()
        }
    }

    fun buildResponse(): ModelResponse {
        if (!sawEvent) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider stream ended without any data.",
            )
        }
        val resolvedToolCalls =
            toolCalls.toSortedMap().map { (_, accumulator) ->
                ProviderToolCall(
                    id =
                        accumulator.id.toString().takeIf { it.isNotBlank() } ?: throw ModelProviderException(
                            kind = ModelProviderFailureKind.Response,
                            userMessage = "Provider stream returned a tool call without an id.",
                        ),
                    name =
                        accumulator.name.toString().takeIf { it.isNotBlank() } ?: throw ModelProviderException(
                            kind = ModelProviderFailureKind.Response,
                            userMessage = "Provider stream returned a tool call without a name.",
                        ),
                    argumentsJson = parseToolArguments(accumulator.arguments.toString()),
                )
            }
        val assistantMessage = assistantText.toString().trim()
        if (assistantMessage.isBlank() && resolvedToolCalls.isEmpty()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider stream ended without an assistant message.",
            )
        }
        return ModelResponse(
            text = assistantMessage,
            providerRequestId = providerRequestId,
            finishReason =
                when {
                    resolvedToolCalls.isNotEmpty() || finishReason == "tool_use" -> "tool_use"
                    finishReason.isNullOrBlank() -> "stop"
                    else -> finishReason.orEmpty()
                },
            toolCalls = resolvedToolCalls,
            modelId = modelId,
            usage = usage,
        )
    }

    fun hasSeenEvent(): Boolean = sawEvent

    fun canCompleteWithoutTerminalSignal(): Boolean = sawEvent && (sawMessageStop || !finishReason.isNullOrBlank())

    private fun mergeUsage(
        current: ProviderUsage?,
        next: AnthropicUsage?,
    ): ProviderUsage? {
        if (next == null && current == null) {
            return null
        }
        val inputTokens = next?.inputTokens ?: current?.inputTokens
        val outputTokens = next?.outputTokens ?: current?.outputTokens
        val totalTokens =
            if (inputTokens != null && outputTokens != null) {
                inputTokens + outputTokens
            } else {
                current?.totalTokens
            }
        return ProviderUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
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
                userMessage = "Provider returned malformed tool arguments.",
                details = arguments.take(500),
                cause = error,
            )
        }

    private data class ToolCallAccumulator(
        val id: StringBuilder = StringBuilder(),
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
    )
}

private fun AnthropicUsage.toProviderUsage(): ProviderUsage {
    val totalTokens =
        if (inputTokens != null && outputTokens != null) {
            inputTokens + outputTokens
        } else {
            null
        }
    return ProviderUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
    )
}

private fun flushPendingAnthropicEvent(
    pendingDataLines: MutableList<String>,
    accumulator: AnthropicStreamAccumulator,
    onEvent: (ModelStreamEvent) -> Unit,
    onCompleted: (ModelResponse) -> Unit,
): Boolean {
    if (pendingDataLines.isEmpty()) {
        return false
    }
    val data = pendingDataLines.joinToString(separator = "\n").trim()
    pendingDataLines.clear()
    if (data.isBlank()) {
        return false
    }
    if (data == "[DONE]") {
        onCompleted(accumulator.buildResponse())
        return true
    }
    val envelopeType =
        runCatching {
            Json
                .parseToJsonElement(data)
                .jsonObject["type"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()
    accumulator.applyEnvelope(data).forEach(onEvent)
    if (envelopeType == "message_stop") {
        onCompleted(accumulator.buildResponse())
        return true
    }
    return false
}
