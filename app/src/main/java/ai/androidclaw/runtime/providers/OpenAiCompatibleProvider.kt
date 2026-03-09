package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class OpenAiCompatibleProvider(
    private val settingsDataStore: SettingsDataStore,
    private val providerSecretStore: ProviderSecretStore,
    private val baseHttpClient: OkHttpClient,
    private val json: Json,
) : ModelProvider {
    override val id: String = ProviderType.OpenAiCompatible.providerId

    override suspend fun generate(request: ModelRequest): ModelResponse {
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

        val payload = OpenAiChatCompletionsRequest(
            model = settings.openAiModelId,
            messages = buildMessages(request),
            tools = buildTools(request),
        )
        val body = json.encodeToString(OpenAiChatCompletionsRequest.serializer(), payload)
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpClient = baseHttpClient.newBuilder()
            .callTimeout(settings.openAiTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .connectTimeout(settings.openAiTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(settings.openAiTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(settings.openAiTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .apply {
                request.requestId?.let { header("X-Request-Id", it) }
            }
            .post(body)
            .build()

        try {
            httpClient.newCall(httpRequest).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw mapFailure(response.code, rawBody)
                }
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
        } catch (error: SocketTimeoutException) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Timeout,
                userMessage = "Provider request timed out.",
                details = "Timed out after ${settings.openAiTimeoutSeconds} seconds.",
                cause = error,
            )
        } catch (error: InterruptedIOException) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Timeout,
                userMessage = "Provider request timed out.",
                details = "Timed out after ${settings.openAiTimeoutSeconds} seconds.",
                cause = error,
            )
        } catch (error: IOException) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Network,
                userMessage = "Provider request failed due to a network error.",
                details = error.message,
                cause = error,
            )
        }
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

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val MAX_ERROR_BODY_CHARS = 500
    }
}

@Serializable
private data class OpenAiChatCompletionsRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    val tools: List<OpenAiToolDefinition>? = null,
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
    val message: OpenAiChatMessage = OpenAiChatMessage(role = "assistant"),
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
private data class OpenAiErrorEnvelope(
    val error: OpenAiErrorPayload? = null,
)

@Serializable
private data class OpenAiErrorPayload(
    val message: String? = null,
    @SerialName("type")
    val kind: String? = null,
    val code: String? = null,
)
