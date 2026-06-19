package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.firstProviderEndpointPolicyError
import ai.androidclaw.data.normalizeProviderTimeoutSeconds
import ai.androidclaw.runtime.tools.TOOL_REGISTRY_ARGUMENT_LIST_MAX_ITEMS
import ai.androidclaw.runtime.tools.TOOL_REGISTRY_ARGUMENT_NAME_MAX_CHARS
import ai.androidclaw.runtime.tools.TOOL_REGISTRY_NAME_MAX_CHARS
import ai.androidclaw.runtime.tools.TOOL_REGISTRY_PERMISSION_LIST_MAX_ITEMS
import ai.androidclaw.runtime.tools.ToolDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal val PROVIDER_JSON_MEDIA_TYPE = "application/json".toMediaType()
internal const val PROVIDER_EVENT_STREAM_CONTENT_TYPE_PREFIX = "text/event-stream"
internal const val PROVIDER_SSE_DONE_SENTINEL = "[DONE]"
internal const val MAX_PROVIDER_ERROR_BODY_CHARS = 500
internal const val MAX_PROVIDER_REQUEST_MESSAGES = 256
internal const val MAX_PROVIDER_REQUEST_TEXT_CHARS = 40_000
internal const val MAX_PROVIDER_REQUEST_ID_CHARS = 256
internal const val MAX_PROVIDER_REQUEST_TOOLS = 100
internal const val MAX_PROVIDER_REQUEST_TOOL_DESCRIPTION_CHARS = 500
internal const val MAX_PROVIDER_REQUEST_TOOL_ALIASES = 10
internal const val MAX_PROVIDER_REQUEST_TOOL_ALIAS_CHARS = 80
internal const val MAX_PROVIDER_REQUEST_TOOL_SCHEMA_DEPTH = 8
internal const val MAX_PROVIDER_REQUEST_TOOL_SCHEMA_ENTRIES = 50
internal const val MAX_PROVIDER_REQUEST_TOOL_SCHEMA_STRING_CHARS = 1_000

internal data class ResolvedRequestConfig(
    val endpointSettings: ProviderEndpointSettings,
    val apiKey: String,
    val url: HttpUrl,
    val httpClient: OkHttpClient,
)

internal data class ProviderStreamContext(
    val endpointSettings: ProviderEndpointSettings,
    val httpClient: OkHttpClient,
    val request: Request,
    val streamStarted: () -> Boolean,
    val canCompleteWithoutTerminalSignal: () -> Boolean,
    val buildResponse: () -> ModelResponse,
    val handleDataEvent: (String, (ModelStreamEvent) -> Unit, (ModelResponse) -> Unit) -> Boolean,
)

internal fun validateRemoteProviderSettings(
    providerType: ProviderType,
    settings: ProviderEndpointSettings,
    apiKey: String?,
) {
    if (settings.baseUrl.isBlank()) {
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Configuration,
            userMessage = "Provider base URL is required.",
        )
    }
    settings.firstProviderEndpointPolicyError(providerType = providerType)?.let { issue ->
        throw invalidEndpointFailure(
            baseUrl = settings.baseUrl,
            detail = issue.message,
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

internal fun ModelRequest.toBoundedProviderRequest(providerName: String): ModelRequest =
    copy(
        sessionId = sessionId.toBoundedProviderRequestId(),
        requestId = requestId?.toBoundedProviderRequestId()?.takeIf { it.isNotBlank() },
        systemPrompt = systemPrompt.toBoundedProviderRequestText(),
        messageHistory =
            messageHistory
                .takeLast(MAX_PROVIDER_REQUEST_MESSAGES)
                .map { message -> message.toBoundedProviderRequestMessage(providerName) },
        toolDescriptors =
            toolDescriptors
                .take(MAX_PROVIDER_REQUEST_TOOLS)
                .map { descriptor -> descriptor.toBoundedProviderToolDescriptor(providerName) },
    )

private fun ModelMessage.toBoundedProviderRequestMessage(providerName: String): ModelMessage =
    copy(
        content = content.toBoundedProviderRequestText(),
        toolCallId = toolCallId?.toBoundedProviderRequestId()?.takeIf { it.isNotBlank() },
        toolName = toolName?.toBoundedProviderToolCallName()?.takeIf { it.isNotBlank() },
        toolCalls =
            toolCalls
                .requireProviderToolCallLimit(providerName)
                .map { toolCall -> toolCall.toBoundedProviderRequestToolCall(providerName) },
    )

private fun ProviderToolCall.toBoundedProviderRequestToolCall(providerName: String): ProviderToolCall =
    copy(
        id = id.toBoundedProviderToolCallId(),
        name = name.toBoundedProviderToolCallName(),
        argumentsJson = argumentsJson.requireProviderRequestToolArgumentsWithinLimit(providerName),
    )

private fun String.toBoundedProviderRequestText(): String = take(MAX_PROVIDER_REQUEST_TEXT_CHARS)

private fun String.toBoundedProviderRequestId(): String = trim().take(MAX_PROVIDER_REQUEST_ID_CHARS)

private fun ToolDescriptor.toBoundedProviderToolDescriptor(providerName: String): ToolDescriptor {
    validateProviderToolDescriptorName(providerName)
    return copy(
        description = description.take(MAX_PROVIDER_REQUEST_TOOL_DESCRIPTION_CHARS),
        aliases =
            aliases
                .take(MAX_PROVIDER_REQUEST_TOOL_ALIASES)
                .map { alias -> alias.take(MAX_PROVIDER_REQUEST_TOOL_ALIAS_CHARS) },
        requiredPermissions =
            requiredPermissions
                .take(TOOL_REGISTRY_PERMISSION_LIST_MAX_ITEMS)
                .map { permission ->
                    permission.copy(
                        permission = permission.permission.take(TOOL_REGISTRY_NAME_MAX_CHARS),
                        displayName = permission.displayName.take(TOOL_REGISTRY_NAME_MAX_CHARS),
                    )
                },
        availability =
            availability.copy(
                reason = availability.reason?.take(MAX_PROVIDER_REQUEST_TOOL_SCHEMA_STRING_CHARS),
            ),
        arguments =
            arguments
                .take(TOOL_REGISTRY_ARGUMENT_LIST_MAX_ITEMS)
                .map { argument ->
                    argument.copy(
                        description = argument.description.take(MAX_PROVIDER_REQUEST_TOOL_SCHEMA_STRING_CHARS),
                    )
                },
        inputSchema = inputSchema.toBoundedProviderToolInputSchema(),
    )
}

private fun ToolDescriptor.validateProviderToolDescriptorName(providerName: String) {
    if (name.isBlank()) {
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "$providerName request included a tool descriptor without a name.",
        )
    }
    if (name.length > TOOL_REGISTRY_NAME_MAX_CHARS) {
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "$providerName request included an oversized tool descriptor name.",
            details = name.take(MAX_PROVIDER_ERROR_BODY_CHARS),
        )
    }
}

private fun JsonObject.toBoundedProviderToolInputSchema(): JsonObject =
    toBoundedProviderToolSchemaElement(depth = 0) as? JsonObject ?: buildJsonObject {
        put("type", JsonPrimitive("object"))
    }

private fun JsonElement.toBoundedProviderToolSchemaElement(depth: Int): JsonElement {
    if (depth >= MAX_PROVIDER_REQUEST_TOOL_SCHEMA_DEPTH) {
        return JsonPrimitive("[omitted]")
    }
    return when (this) {
        is JsonObject ->
            buildJsonObject {
                entries
                    .take(MAX_PROVIDER_REQUEST_TOOL_SCHEMA_ENTRIES)
                    .forEach { (key, value) ->
                        put(
                            key.take(TOOL_REGISTRY_ARGUMENT_NAME_MAX_CHARS),
                            value.toBoundedProviderToolSchemaElement(depth + 1),
                        )
                    }
            }

        is JsonArray ->
            buildJsonArray {
                take(MAX_PROVIDER_REQUEST_TOOL_SCHEMA_ENTRIES).forEach { value ->
                    add(value.toBoundedProviderToolSchemaElement(depth + 1))
                }
            }

        JsonNull -> JsonNull
        is JsonPrimitive -> toBoundedProviderToolSchemaPrimitive()
    }
}

private fun JsonPrimitive.toBoundedProviderToolSchemaPrimitive(): JsonPrimitive =
    if (isString) {
        JsonPrimitive(content.take(MAX_PROVIDER_REQUEST_TOOL_SCHEMA_STRING_CHARS))
    } else {
        this
    }

private fun kotlinx.serialization.json.JsonObject.requireProviderRequestToolArgumentsWithinLimit(
    providerName: String,
): kotlinx.serialization.json.JsonObject {
    val serialized = toString()
    if (serialized.length > MAX_PROVIDER_TOOL_ARGUMENT_CHARS) {
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Response,
            userMessage = "$providerName request included oversized tool arguments.",
            details = serialized.take(MAX_PROVIDER_ERROR_BODY_CHARS),
        )
    }
    return this
}

internal fun OkHttpClient.withProviderTimeouts(settings: ProviderEndpointSettings): OkHttpClient {
    val timeoutSeconds = normalizeProviderTimeoutSeconds(settings.timeoutSeconds).toLong()
    return newBuilder()
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()
}

internal fun OkHttpClient.withStreamingProviderTimeouts(): OkHttpClient =
    newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

internal fun streamProviderEvents(
    buildContext: suspend () -> ProviderStreamContext,
    mapHttpFailure: (Int, String) -> ModelProviderException,
    fallbackForHttpResponse: suspend (Int, String) -> ModelResponse? = { _, _ -> null },
    fallbackForNonEventStream: suspend (String, String) -> ModelResponse? = { _, _ -> null },
): Flow<ModelStreamEvent> =
    channelFlow {
        val context =
            try {
                buildContext()
            } catch (error: Exception) {
                close(error)
                return@channelFlow
            }
        val completed = AtomicBoolean(false)
        val cancelledByCollector = AtomicBoolean(false)
        val call = context.httpClient.withStreamingProviderTimeouts().newCall(context.request)

        fun complete(response: ModelResponse) {
            if (completed.compareAndSet(false, true)) {
                trySend(ModelStreamEvent.Completed(response))
                close()
            }
        }

        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val rawBody = response.body?.string().orEmpty()
                        val fallback = fallbackForHttpResponse(response.code, rawBody)
                        if (fallback != null) {
                            complete(fallback)
                            return@use
                        }
                        throw mapHttpFailure(response.code, rawBody)
                    }

                    val body =
                        response.body ?: throw ModelProviderException(
                            kind = ModelProviderFailureKind.Response,
                            userMessage = "Provider stream ended without a response body.",
                        )
                    val contentType = body.contentType()?.toString().orEmpty()
                    if (!contentType.startsWith(PROVIDER_EVENT_STREAM_CONTENT_TYPE_PREFIX)) {
                        val rawBody = body.string()
                        val fallback = fallbackForNonEventStream(contentType, rawBody)
                        if (fallback != null) {
                            complete(fallback)
                            return@use
                        }
                        throw ModelProviderException(
                            kind = ModelProviderFailureKind.Response,
                            userMessage = "Provider stream did not return an event stream.",
                            details = "$contentType ${rawBody.take(MAX_PROVIDER_ERROR_BODY_CHARS)}".trim(),
                        )
                    }

                    val doneSeen =
                        readSseDataEvents(body.source()) { data ->
                            context.handleDataEvent(
                                data,
                                { event -> trySend(event) },
                                ::complete,
                            )
                        }

                    if (!doneSeen) {
                        if (!context.canCompleteWithoutTerminalSignal()) {
                            throw streamInterruptedFailure(
                                details = "Provider stream ended before a terminal event was received.",
                            )
                        }
                        complete(context.buildResponse())
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
                        mapProviderStreamingFailure(
                            settings = context.endpointSettings,
                            throwable = error,
                            streamStarted = context.streamStarted(),
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

internal fun mapProviderStreamingFailure(
    settings: ProviderEndpointSettings,
    throwable: Throwable?,
    streamStarted: Boolean,
): ModelProviderException {
    if (throwable is ModelProviderException) {
        return throwable
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

internal fun readSseDataEvents(
    source: BufferedSource,
    handleDataEvent: (String) -> Boolean,
): Boolean {
    val pendingDataLines = mutableListOf<String>()
    var doneSeen = false
    while (!doneSeen) {
        val line = source.readUtf8Line() ?: break
        if (line.isBlank()) {
            doneSeen = flushPendingDataEvent(pendingDataLines, handleDataEvent)
            continue
        }
        if (line.startsWith("data:")) {
            pendingDataLines += line.removePrefix("data:").trimStart()
        }
    }
    if (!doneSeen) {
        doneSeen = flushPendingDataEvent(pendingDataLines, handleDataEvent)
    }
    return doneSeen
}

private fun flushPendingDataEvent(
    pendingDataLines: MutableList<String>,
    handleDataEvent: (String) -> Boolean,
): Boolean {
    if (pendingDataLines.isEmpty()) {
        return false
    }
    val data = pendingDataLines.joinToString(separator = "\n").trim()
    pendingDataLines.clear()
    if (data.isBlank()) {
        return false
    }
    return handleDataEvent(data)
}
