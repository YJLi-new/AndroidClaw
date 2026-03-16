package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class OpenAiCompatibleProvider(
    private val settingsDataStore: SettingsDataStore,
    private val providerSecretStore: ProviderSecretStore,
    private val baseHttpClient: OkHttpClient,
    private val json: Json,
) : ModelProvider {
    override val id: String = ProviderType.OpenAiCompatible.providerId
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsStreamingText = true,
        supportsStreamingToolCalls = true,
    )

    override suspend fun generate(request: ModelRequest): ModelResponse {
        val config = resolveConfig()
        val payload = OpenAiChatCompletionsRequest(
            model = config.settings.openAiModelId,
            messages = buildMessages(request),
            tools = buildTools(request),
        )
        val httpRequest = buildHttpRequest(
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
            throw timeoutFailure(config.settings, error)
        } catch (error: InterruptedIOException) {
            throw timeoutFailure(config.settings, error)
        } catch (error: IOException) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Network,
                userMessage = "Provider request failed due to a network error.",
                details = error.message,
                cause = error,
            )
        }
    }

    override fun streamGenerate(request: ModelRequest): Flow<ModelStreamEvent> = callbackFlow {
        val config = try {
            resolveConfig()
        } catch (error: Exception) {
            close(error)
            return@callbackFlow
        }
        val payload = OpenAiChatCompletionsRequest(
            model = config.settings.openAiModelId,
            messages = buildMessages(request),
            tools = buildTools(request),
            stream = true,
        )
        val httpRequest = buildHttpRequest(
            url = config.url,
            apiKey = config.apiKey,
            requestId = request.requestId,
            payload = payload,
        )
        val accumulator = OpenAiStreamAccumulator(json)
        val completed = AtomicBoolean(false)

        fun finishWithResponse(response: ModelResponse) {
            if (completed.compareAndSet(false, true)) {
                trySend(ModelStreamEvent.Completed(response))
                close()
            }
        }

        fun finishWithError(error: Throwable) {
            if (completed.compareAndSet(false, true)) {
                close(error)
            }
        }

        fun fallbackToBatch() {
            if (!completed.compareAndSet(false, true)) {
                return
            }
            launch {
                try {
                    val response = withContext(Dispatchers.IO) { generate(request) }
                    trySend(ModelStreamEvent.Completed(response))
                    close()
                } catch (error: Exception) {
                    close(error)
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (completed.get()) {
                    return
                }
                val contentType = response.body?.contentType()?.toString().orEmpty()
                if (!contentType.startsWith(EVENT_STREAM_CONTENT_TYPE_PREFIX)) {
                    eventSource.cancel()
                    fallbackToBatch()
                }
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                if (completed.get()) {
                    return
                }
                if (data == DONE_SENTINEL) {
                    try {
                        finishWithResponse(accumulator.buildResponse())
                    } catch (error: Exception) {
                        finishWithError(error)
                    }
                    return
                }
                if (data.isBlank()) {
                    return
                }
                try {
                    accumulator.applyChunk(data).forEach { event ->
                        trySend(event)
                    }
                } catch (error: Exception) {
                    finishWithError(error)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (completed.get()) {
                    return
                }
                try {
                    finishWithResponse(accumulator.buildResponse())
                } catch (error: Exception) {
                    finishWithError(error)
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                if (completed.get() || channel.isClosedForSend) {
                    return
                }
                val rawBody = response?.body?.string().orEmpty()
                if (response != null && shouldFallbackFromStreaming(response.code, rawBody)) {
                    fallbackToBatch()
                    return
                }
                finishWithError(mapStreamingFailure(config.settings, response, rawBody, t))
            }
        }

        val eventSource = EventSources.createFactory(config.httpClient).newEventSource(httpRequest, listener)
        awaitClose {
            eventSource.cancel()
        }
    }

    private suspend fun resolveConfig(): ResolvedRequestConfig {
        val settings = settingsDataStore.settings.first()
        val apiKey = providerSecretStore.readApiKey(ProviderType.OpenAiCompatible)

        validateSettings(settings, apiKey)

        val url = settings.openAiBaseUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegment("chat")
            ?.addPathSegment("completions")
            ?.build()
            ?: throw ModelProviderException(
                kind = ModelProviderFailureKind.Configuration,
                userMessage = "Provider base URL is invalid.",
                details = "Configured base URL: ${settings.openAiBaseUrl}",
            )
        return ResolvedRequestConfig(
            settings = settings,
            apiKey = apiKey.orEmpty(),
            url = url,
            httpClient = baseHttpClient.newBuilder()
                .callTimeout(settings.openAiTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .connectTimeout(settings.openAiTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(settings.openAiTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(settings.openAiTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build(),
        )
    }

    private fun validateSettings(settings: ProviderSettingsSnapshot, apiKey: String?) {
        if (settings.openAiBaseUrl.isBlank()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Configuration,
                userMessage = "Provider base URL is required.",
            )
        }
        if (settings.openAiModelId.isBlank()) {
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

    private fun buildHttpRequest(
        url: HttpUrl,
        apiKey: String,
        requestId: String?,
        payload: OpenAiChatCompletionsRequest,
    ): Request {
        val body = json.encodeToString(OpenAiChatCompletionsRequest.serializer(), payload)
            .toRequestBody(JSON_MEDIA_TYPE)
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .apply {
                requestId?.let { header("X-Request-Id", it) }
            }
            .post(body)
            .build()
    }

    private fun buildMessages(request: ModelRequest): List<OpenAiChatMessage> {
        val messages = mutableListOf<OpenAiChatMessage>()
        if (request.systemPrompt.isNotBlank()) {
            messages += OpenAiChatMessage(
                role = "system",
                content = request.systemPrompt,
            )
        }
        messages += request.messageHistory.map(::toOpenAiChatMessage)
        return messages
    }

    private fun buildTools(request: ModelRequest): List<OpenAiToolDefinition>? {
        return request.toolDescriptors
            .takeIf { it.isNotEmpty() }
            ?.map { descriptor ->
                OpenAiToolDefinition(
                    function = OpenAiFunctionDefinition(
                        name = descriptor.name,
                        description = descriptor.description,
                        parameters = descriptor.inputSchema,
                    ),
                )
            }
    }

    private fun toOpenAiChatMessage(message: ModelMessage): OpenAiChatMessage {
        return when (message.role) {
            ModelMessageRole.System -> OpenAiChatMessage(
                role = "system",
                content = message.content,
            )

            ModelMessageRole.User -> OpenAiChatMessage(
                role = "user",
                content = message.content,
            )

            ModelMessageRole.Assistant -> OpenAiChatMessage(
                role = "assistant",
                content = message.content.takeIf { it.isNotBlank() },
                toolCalls = message.toolCalls
                    .takeIf { it.isNotEmpty() }
                    ?.map { toolCall ->
                        OpenAiToolCall(
                            id = toolCall.id,
                            function = OpenAiToolFunctionCall(
                                name = toolCall.name,
                                arguments = json.encodeToString(JsonObject.serializer(), toolCall.argumentsJson),
                            ),
                        )
                    },
            )

            ModelMessageRole.Tool -> OpenAiChatMessage(
                role = "tool",
                content = message.content,
                toolCallId = message.toolCallId ?: throw ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage = "Provider request included a tool result without a tool call id.",
                ),
            )
        }
    }

    private fun parseBatchResponse(rawBody: String): ModelResponse {
        val parsed = try {
            json.decodeFromString(OpenAiChatCompletionsResponse.serializer(), rawBody)
        } catch (error: SerializationException) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider returned malformed JSON.",
                details = rawBody.take(MAX_ERROR_BODY_CHARS),
                cause = error,
            )
        }

        val choice = parsed.choices.firstOrNull()
            ?: throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider response did not contain an assistant message.",
                details = rawBody.take(MAX_ERROR_BODY_CHARS),
            )
        val assistantText = choice.message.content?.trim().orEmpty()
        val toolCalls = choice.message.toolCalls.orEmpty().map(::toProviderToolCall)
        if (toolCalls.isNotEmpty()) {
            return ModelResponse(
                text = assistantText,
                providerRequestId = parsed.id,
                finishReason = "tool_use",
                toolCalls = toolCalls,
            )
        }
        if (assistantText.isBlank()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider response did not contain an assistant message.",
                details = rawBody.take(MAX_ERROR_BODY_CHARS),
            )
        }

        return ModelResponse(
            text = assistantText,
            providerRequestId = parsed.id,
            finishReason = choice.finishReason ?: "stop",
        )
    }

    private fun toProviderToolCall(toolCall: OpenAiToolCall): ProviderToolCall {
        val parsedArguments = try {
            if (toolCall.function.arguments.isBlank()) {
                buildJsonObject {}
            } else {
                json.parseToJsonElement(toolCall.function.arguments).jsonObject
            }
        } catch (error: Exception) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider returned malformed tool arguments.",
                details = toolCall.function.arguments.take(MAX_ERROR_BODY_CHARS),
                cause = error,
            )
        }
        return ProviderToolCall(
            id = toolCall.id,
            name = toolCall.function.name,
            argumentsJson = parsedArguments,
        )
    }

    private fun shouldFallbackFromStreaming(statusCode: Int, rawBody: String): Boolean {
        if (statusCode in STREAMING_FALLBACK_STATUS_CODES) {
            return true
        }
        if (statusCode !in STREAMING_MAYBE_UNSUPPORTED_STATUS_CODES) {
            return false
        }
        val normalized = rawBody.lowercase()
        return normalized.contains("stream") && (
            normalized.contains("unsupported") ||
                normalized.contains("not implemented") ||
                normalized.contains("not support")
            )
    }

    private fun mapStreamingFailure(
        settings: ProviderSettingsSnapshot,
        response: Response?,
        rawBody: String,
        throwable: Throwable?,
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
            is IOException -> ModelProviderException(
                kind = ModelProviderFailureKind.Network,
                userMessage = "Provider request failed due to a network error.",
                details = throwable.message,
                cause = throwable,
            )

            else -> ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider streaming failed.",
                details = throwable?.message,
                cause = throwable,
            )
        }
    }

    private fun timeoutFailure(
        settings: ProviderSettingsSnapshot,
        error: Throwable,
    ): ModelProviderException {
        return ModelProviderException(
            kind = ModelProviderFailureKind.Timeout,
            userMessage = "Provider request timed out.",
            details = "Timed out after ${settings.openAiTimeoutSeconds} seconds.",
            cause = error,
        )
    }

    private fun mapFailure(statusCode: Int, rawBody: String): ModelProviderException {
        val errorMessage = runCatching {
            json.decodeFromString(OpenAiErrorEnvelope.serializer(), rawBody).error?.message
        }.getOrNull().orEmpty()

        return when (statusCode) {
            401, 403 -> ModelProviderException(
                kind = ModelProviderFailureKind.Authentication,
                userMessage = "Provider authentication failed.",
                details = errorMessage.ifBlank { rawBody.take(MAX_ERROR_BODY_CHARS) },
            )

            else -> ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider request failed with HTTP $statusCode.",
                details = errorMessage.ifBlank { rawBody.take(MAX_ERROR_BODY_CHARS) },
            )
        }
    }

    private data class ResolvedRequestConfig(
        val settings: ProviderSettingsSnapshot,
        val apiKey: String,
        val url: HttpUrl,
        val httpClient: OkHttpClient,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val DONE_SENTINEL = "[DONE]"
        const val EVENT_STREAM_CONTENT_TYPE_PREFIX = "text/event-stream"
        const val MAX_ERROR_BODY_CHARS = 500
        val STREAMING_FALLBACK_STATUS_CODES = setOf(404, 405, 501)
        val STREAMING_MAYBE_UNSUPPORTED_STATUS_CODES = setOf(400, 415, 422)
    }
}

@Serializable
private data class OpenAiChatCompletionsRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    val tools: List<OpenAiToolDefinition>? = null,
    val stream: Boolean = false,
)

@Serializable
private data class OpenAiChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCall>? = null,
)

@Serializable
private data class OpenAiToolDefinition(
    val type: String = "function",
    val function: OpenAiFunctionDefinition,
)

@Serializable
private data class OpenAiFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
private data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiToolFunctionCall,
)

@Serializable
private data class OpenAiToolFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
private data class OpenAiChatCompletionsResponse(
    val id: String? = null,
    val choices: List<OpenAiChatChoice> = emptyList(),
)

@Serializable
private data class OpenAiChatChoice(
    val message: OpenAiAssistantMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
private data class OpenAiAssistantMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCall>? = null,
)

@Serializable
private data class OpenAiErrorEnvelope(
    val error: OpenAiErrorPayload? = null,
)

@Serializable
private data class OpenAiErrorPayload(
    val message: String? = null,
)

@Serializable
private data class OpenAiChatCompletionsChunkResponse(
    val id: String? = null,
    val choices: List<OpenAiStreamChoice> = emptyList(),
)

@Serializable
private data class OpenAiStreamChoice(
    val index: Int = 0,
    val delta: OpenAiStreamDelta = OpenAiStreamDelta(),
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
private data class OpenAiStreamDelta(
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiStreamToolCallDelta>? = null,
)

@Serializable
private data class OpenAiStreamToolCallDelta(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiStreamToolFunctionDelta? = null,
)

@Serializable
private data class OpenAiStreamToolFunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
)

private class OpenAiStreamAccumulator(
    private val json: Json,
) {
    private val assistantText = StringBuilder()
    private val toolCalls = linkedMapOf<Int, ToolCallAccumulator>()
    private var providerRequestId: String? = null
    private var finishReason: String? = null
    private var sawChunk = false

    fun applyChunk(rawChunk: String): List<ModelStreamEvent> {
        val chunk = try {
            json.decodeFromString(OpenAiChatCompletionsChunkResponse.serializer(), rawChunk)
        } catch (error: SerializationException) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider returned malformed SSE chunk.",
                details = rawChunk.take(500),
                cause = error,
            )
        }

        sawChunk = true
        providerRequestId = chunk.id ?: providerRequestId
        return buildList {
            chunk.choices.forEach { choice ->
                choice.delta.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { contentPart ->
                        assistantText.append(contentPart)
                        add(ModelStreamEvent.TextDelta(contentPart))
                    }
                choice.delta.toolCalls.orEmpty().forEach { toolCallDelta ->
                    val accumulator = toolCalls.getOrPut(toolCallDelta.index) { ToolCallAccumulator() }
                    toolCallDelta.id
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { idPart ->
                            accumulator.id.append(idPart)
                            add(ModelStreamEvent.ToolCallDelta(index = toolCallDelta.index, idPart = idPart))
                        }
                    toolCallDelta.function?.name
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { namePart ->
                            accumulator.name.append(namePart)
                            add(ModelStreamEvent.ToolCallDelta(index = toolCallDelta.index, namePart = namePart))
                        }
                    toolCallDelta.function?.arguments
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { argumentsPart ->
                            accumulator.arguments.append(argumentsPart)
                            add(ModelStreamEvent.ToolCallDelta(index = toolCallDelta.index, argumentsPart = argumentsPart))
                        }
                }
                choice.finishReason?.let { finishReason = it }
            }
        }
    }

    fun buildResponse(): ModelResponse {
        if (!sawChunk) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider stream ended without any data.",
            )
        }
        val resolvedToolCalls = toolCalls.toSortedMap().map { (_, accumulator) ->
            ProviderToolCall(
                id = accumulator.id.toString().takeIf { it.isNotBlank() } ?: throw ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage = "Provider stream returned a tool call without an id.",
                ),
                name = accumulator.name.toString().takeIf { it.isNotBlank() } ?: throw ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage = "Provider stream returned a tool call without a name.",
                ),
                argumentsJson = parseToolArguments(accumulator.arguments.toString()),
            )
        }
        val assistantMessage = assistantText.toString()
        if (assistantMessage.isBlank() && resolvedToolCalls.isEmpty()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider stream ended without an assistant message.",
            )
        }
        val normalizedFinishReason = when {
            resolvedToolCalls.isNotEmpty() -> "tool_use"
            finishReason.isNullOrBlank() -> "stop"
            finishReason == "tool_calls" -> "tool_use"
            else -> finishReason.orEmpty()
        }
        return ModelResponse(
            text = assistantMessage,
            providerRequestId = providerRequestId,
            finishReason = normalizedFinishReason,
            toolCalls = resolvedToolCalls,
        )
    }

    private fun parseToolArguments(arguments: String): JsonObject {
        return try {
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
    }

    private data class ToolCallAccumulator(
        val id: StringBuilder = StringBuilder(),
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
    )
}
