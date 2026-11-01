package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

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
        val boundedRequest = request.toBoundedProviderRequest(providerName = "Provider")
        val config = resolveConfig()
        val payload = buildRequestPayload(boundedRequest, config.endpointSettings, stream = false)
        val httpRequest =
            buildHttpRequest(
                url = config.url,
                apiKey = config.apiKey,
                requestId = boundedRequest.requestId,
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
        request.toBoundedProviderRequest(providerName = "Provider").let { boundedRequest ->
            streamProviderEvents(
                buildContext = {
                    val config = resolveConfig()
                    val payload = buildRequestPayload(boundedRequest, config.endpointSettings, stream = true)
                    val httpRequest =
                        buildHttpRequest(
                            url = config.url,
                            apiKey = config.apiKey,
                            requestId = boundedRequest.requestId,
                            payload = payload,
                        )
                    val accumulator = AnthropicStreamAccumulator(json)
                    ProviderStreamContext(
                        endpointSettings = config.endpointSettings,
                        httpClient = config.httpClient,
                        request = httpRequest,
                        streamStarted = accumulator::hasSeenEvent,
                        canCompleteWithoutTerminalSignal = accumulator::canCompleteWithoutTerminalSignal,
                        buildResponse = accumulator::buildResponse,
                        handleDataEvent = { data, onEvent, onCompleted ->
                            handleAnthropicSseData(
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
        }

    private suspend fun resolveConfig(): ResolvedRequestConfig {
        val settings = settingsDataStore.settings.first()
        val endpointSettings = settings.endpointSettings(ProviderType.Anthropic)
        val apiKey = providerSecretStore.readApiKey(ProviderType.Anthropic)

        validateRemoteProviderSettings(
            providerType = ProviderType.Anthropic,
            settings = endpointSettings,
            apiKey = apiKey,
        )

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
            httpClient = baseHttpClient.withProviderTimeouts(endpointSettings),
        )
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
                buildList {
                    request.toolDescriptors.forEach { descriptor ->
                        add(
                            AnthropicToolDefinition(
                                name = descriptor.name,
                                description = descriptor.description,
                                inputSchema = descriptor.inputSchema,
                            ),
                        )
                    }
                    if (request.hostedWebSearch) {
                        add(anthropicHostedWebSearchTool())
                    }
                }.takeIf { it.isNotEmpty() },
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
                .toRequestBody(PROVIDER_JSON_MEDIA_TYPE)
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
                    details = rawBody.take(MAX_PROVIDER_ERROR_BODY_CHARS),
                    cause = error,
                )
            }

        val assistantText =
            parsed.content
                .filter { it.type == "text" }
                .joinToString(separator = "") { it.text.orEmpty() }
                .trim()
                .toBoundedProviderAssistantText()
        val toolCalls =
            parsed.content
                .filter { it.type == "tool_use" }
                .requireProviderToolCallLimit(providerName = "Provider")
                .map { block ->
                    val id =
                        block.id ?: throw ModelProviderException(
                            kind = ModelProviderFailureKind.Response,
                            userMessage = "Provider returned a tool call without an id.",
                        )
                    val name =
                        block.name ?: throw ModelProviderException(
                            kind = ModelProviderFailureKind.Response,
                            userMessage = "Provider returned a tool call without a name.",
                        )
                    ProviderToolCall(
                        id = id.toBoundedProviderToolCallId(),
                        name = name.toBoundedProviderToolCallName(),
                        argumentsJson =
                            (block.input ?: buildJsonObject {})
                                .requireProviderToolArgumentsWithinLimit(providerName = "Provider"),
                    )
                }

        if (assistantText.isBlank() && toolCalls.isEmpty()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "Provider response did not contain an assistant message.",
                details = rawBody.take(MAX_PROVIDER_ERROR_BODY_CHARS),
            )
        }
        val webSearch = AnthropicWebSearchCollector()
        parsed.content.forEach { block ->
            when (block.type) {
                "server_tool_use" -> webSearch.addServerToolInput(block.name, block.input)
                "web_search_tool_result" -> webSearch.addSearchResults(block.content)
                "text" -> block.citations.orEmpty().forEach(webSearch::addCitation)
            }
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
            webSearchQueries = webSearch.queries(),
            webSources = webSearch.sources(),
        )
    }

    private fun mapFailure(
        statusCode: Int,
        rawBody: String,
    ): ModelProviderException {
        val parsedMessage = json.extractProviderErrorMessage(rawBody)

        return when (statusCode) {
            401, 403 ->
                ModelProviderException(
                    kind = ModelProviderFailureKind.Authentication,
                    userMessage = "Provider authentication failed.",
                    details = parsedMessage.ifBlank { rawBody.take(MAX_PROVIDER_ERROR_BODY_CHARS) },
                )

            else ->
                ModelProviderException(
                    kind = ModelProviderFailureKind.Server,
                    userMessage =
                        if (statusCode == 400 && parsedMessage.isNotBlank()) {
                            "Provider rejected the request: $parsedMessage"
                        } else {
                            "Provider request failed with HTTP $statusCode."
                        },
                    details = parsedMessage.ifBlank { rawBody.take(MAX_PROVIDER_ERROR_BODY_CHARS) },
                )
        }
    }

    private companion object {
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

/** Function tools carry description and schema; server tools (web search) carry type and max_uses. */
@Serializable
private data class AnthropicToolDefinition(
    val name: String,
    val description: String? = null,
    @SerialName("input_schema")
    val inputSchema: JsonObject? = null,
    val type: String? = null,
    @SerialName("max_uses")
    val maxUses: Int? = null,
)

private const val ANTHROPIC_WEB_SEARCH_TOOL_TYPE = "web_search_20250305"
private const val ANTHROPIC_WEB_SEARCH_MAX_USES = 5
private const val MAX_SERVER_TOOL_BLOCKS = 16
private const val MAX_SERVER_TOOL_INPUT_CHARS = 2_000

private fun anthropicHostedWebSearchTool(): AnthropicToolDefinition =
    AnthropicToolDefinition(
        name = "web_search",
        type = ANTHROPIC_WEB_SEARCH_TOOL_TYPE,
        maxUses = ANTHROPIC_WEB_SEARCH_MAX_USES,
    )

@Serializable
private data class AnthropicCitation(
    val type: String? = null,
    val url: String? = null,
    val title: String? = null,
)

/**
 * Hosted web search output: `server_tool_use` queries, cited URLs (first), then the URLs of
 * `web_search_tool_result` blocks until the bounded list is full.
 */
private class AnthropicWebSearchCollector {
    private val collector = WebSearchCollector()
    private val results = mutableListOf<WebSource>()

    fun addServerToolInput(
        name: String?,
        input: JsonObject?,
    ) {
        if (name != "web_search") return
        collector.addQuery((input?.get("query") as? JsonPrimitive)?.contentOrNull)
    }

    fun addServerToolInputJson(
        name: String?,
        rawInput: String,
    ) {
        val input = runCatching { Json.parseToJsonElement(rawInput) as? JsonObject }.getOrNull()
        addServerToolInput(name, input)
    }

    fun addCitation(citation: AnthropicCitation) {
        if (citation.type == "web_search_result_location") {
            collector.addSource(citation.url, citation.title)
        }
    }

    fun addSearchResults(content: JsonElement?) {
        (content as? JsonArray).orEmpty().forEach { element ->
            val result = element as? JsonObject ?: return@forEach
            if ((result["type"] as? JsonPrimitive)?.contentOrNull != "web_search_result") return@forEach
            val url = (result["url"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (results.size < WEB_SOURCES_MAX * 2) {
                results += WebSource(url = url, title = (result["title"] as? JsonPrimitive)?.contentOrNull)
            }
        }
    }

    fun queries(): List<String> = collector.queries()

    fun sources(): List<WebSource> {
        results.forEach { collector.addSource(it.url, it.title) }
        results.clear()
        return collector.sources()
    }
}

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
    val citations: List<AnthropicCitation>? = null,
    /** `web_search_tool_result`: a result array, or an error object. */
    val content: JsonElement? = null,
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
    val content: JsonElement? = null,
)

@Serializable
private data class AnthropicStreamDelta(
    val type: String? = null,
    val text: String? = null,
    @SerialName("partial_json")
    val partialJson: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    val citation: AnthropicCitation? = null,
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
    private val webSearch = AnthropicWebSearchCollector()

    /** Hosted `server_tool_use` blocks by index: their input streams like tool input but is not a tool call. */
    private val serverToolInputs = linkedMapOf<Int, Pair<String?, StringBuilder>>()

    fun applyEnvelope(rawEnvelope: String): List<ModelStreamEvent> {
        val envelope =
            try {
                json.decodeFromString(AnthropicStreamEnvelope.serializer(), rawEnvelope)
            } catch (error: SerializationException) {
                throw ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage = "Provider returned malformed SSE event.",
                    details = rawEnvelope.take(MAX_PROVIDER_ERROR_BODY_CHARS),
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
                        requireProviderToolCallCapacity(
                            currentSize = toolCalls.size,
                            isNewToolCall = !toolCalls.containsKey(index),
                            providerName = "Provider",
                        )
                        val accumulator = toolCalls.getOrPut(index) { ToolCallAccumulator() }
                        buildList {
                            envelope.contentBlock.id
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { idPart ->
                                    val boundedIdPart = accumulator.id.appendBoundedProviderToolCallId(idPart)
                                    if (boundedIdPart.isNotEmpty()) {
                                        add(ModelStreamEvent.ToolCallDelta(index = index, idPart = boundedIdPart))
                                    }
                                }
                            envelope.contentBlock.name
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { namePart ->
                                    val boundedNamePart = accumulator.name.appendBoundedProviderToolCallName(namePart)
                                    if (boundedNamePart.isNotEmpty()) {
                                        add(ModelStreamEvent.ToolCallDelta(index = index, namePart = boundedNamePart))
                                    }
                                }
                        }
                    }

                    "server_tool_use" -> {
                        if (serverToolInputs.size < MAX_SERVER_TOOL_BLOCKS) {
                            serverToolInputs[envelope.index ?: 0] = envelope.contentBlock.name to StringBuilder()
                        }
                        emptyList()
                    }

                    "web_search_tool_result" -> {
                        webSearch.addSearchResults(envelope.contentBlock.content)
                        emptyList()
                    }

                    else -> emptyList()
                }
            }

            "content_block_delta" -> {
                val serverToolInput = serverToolInputs[envelope.index ?: 0]?.second
                if (envelope.delta?.type == "input_json_delta" && serverToolInput != null) {
                    if (serverToolInput.length < MAX_SERVER_TOOL_INPUT_CHARS) {
                        serverToolInput.append(
                            envelope.delta.partialJson
                                .orEmpty()
                                .take(MAX_SERVER_TOOL_INPUT_CHARS),
                        )
                    }
                    return emptyList()
                }
                when (envelope.delta?.type) {
                    "citations_delta" -> {
                        envelope.delta.citation?.let(webSearch::addCitation)
                        emptyList()
                    }

                    "text_delta" -> {
                        val textPart = envelope.delta.text.orEmpty()
                        if (textPart.isBlank()) {
                            emptyList()
                        } else {
                            val boundedTextPart = assistantText.appendBoundedProviderAssistantText(textPart)
                            if (boundedTextPart.isEmpty()) {
                                emptyList()
                            } else {
                                listOf(ModelStreamEvent.TextDelta(boundedTextPart))
                            }
                        }
                    }

                    "input_json_delta" -> {
                        val index = envelope.index ?: 0
                        requireProviderToolCallCapacity(
                            currentSize = toolCalls.size,
                            isNewToolCall = !toolCalls.containsKey(index),
                            providerName = "Provider",
                        )
                        val accumulator = toolCalls.getOrPut(index) { ToolCallAccumulator() }
                        val argumentsPart = envelope.delta.partialJson.orEmpty()
                        if (argumentsPart.isBlank()) {
                            emptyList()
                        } else {
                            accumulator.arguments.appendProviderToolArguments(
                                text = argumentsPart,
                                providerName = "Provider",
                            )
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
        val assistantMessage =
            assistantText
                .toString()
                .trim()
                .toBoundedProviderAssistantText()
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
            webSearchQueries =
                webSearch.run {
                    serverToolInputs.values.forEach { (name, input) -> addServerToolInputJson(name, input.toString()) }
                    serverToolInputs.clear()
                    queries()
                },
            webSources = webSearch.sources(),
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
        arguments.requireProviderToolArgumentsWithinLimit(providerName = "Provider").let { boundedArguments ->
            try {
                if (boundedArguments.isBlank()) {
                    buildJsonObject {}
                } else {
                    json.parseToJsonElement(boundedArguments).jsonObject
                }
            } catch (error: Exception) {
                throw ModelProviderException(
                    kind = ModelProviderFailureKind.Response,
                    userMessage = "Provider returned malformed tool arguments.",
                    details = boundedArguments.take(MAX_PROVIDER_ERROR_BODY_CHARS),
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

private fun handleAnthropicSseData(
    data: String,
    accumulator: AnthropicStreamAccumulator,
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
