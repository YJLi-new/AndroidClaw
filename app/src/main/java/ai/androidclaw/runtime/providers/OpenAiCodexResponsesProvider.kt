package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderOAuthCredential
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.firstProviderEndpointPolicyError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    override fun streamGenerate(request: ModelRequest): Flow<ModelStreamEvent> {
        val boundedRequest = request.toBoundedProviderRequest(providerName = "OpenAI Codex")
        val functionNames = ProviderFunctionNameMap.from(boundedRequest.toolDescriptors)
        return streamProviderEvents(
            buildContext = {
                val config = resolveConfig()
                val payload =
                    buildResponsesPayload(
                        request = boundedRequest,
                        endpointSettings = config.endpointSettings,
                        functionNames = functionNames,
                    )
                val httpRequest =
                    buildHttpRequest(
                        url = config.url,
                        credential = config.credential,
                        requestId = boundedRequest.requestId,
                        payload = payload,
                    )
                val accumulator =
                    ResponsesAccumulator(
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
                        handleResponsesSseData(
                            data = data,
                            accumulator = accumulator,
                            onEvent = onEvent,
                            onCompleted = onCompleted,
                        )
                    },
                )
            },
            mapHttpFailure = ::mapFailure,
            fallbackForNonEventStream = { _, rawBody ->
                parseResponsesNonEventStreamResponse(
                    rawBody = rawBody,
                    json = json,
                    functionNames = functionNames,
                )
            },
        )
    }

    private suspend fun resolveConfig(): ResolvedOAuthRequestConfig {
        val settings = settingsDataStore.settings.first()
        val endpointSettings = settings.endpointSettings(ProviderType.OpenAiCodex)
        if (endpointSettings.baseUrl.isBlank()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Configuration,
                userMessage = "Provider base URL is required.",
            )
        }
        endpointSettings.firstProviderEndpointPolicyError(ProviderType.OpenAiCodex)?.let { issue ->
            throw invalidEndpointFailure(
                baseUrl = endpointSettings.baseUrl,
                detail = issue.message,
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
        val url =
            buildResponsesUrl(endpointSettings.baseUrl)
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
            put("tool_choice", "auto")
            put("parallel_tool_calls", false)
            request.systemPrompt
                .takeIf { it.isNotBlank() }
                ?.let { put("instructions", it) }
            put("input", buildResponsesInput(request.messageHistory, functionNames, json))
            request.sessionId
                .takeIf { it.isNotBlank() }
                ?.let { put("prompt_cache_key", it) }
            put(
                "tools",
                buildJsonArray {
                    request.toolDescriptors.forEach { descriptor ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put("name", functionNames.toProviderName(descriptor.name))
                                put("description", descriptor.description)
                                put("strict", false)
                                put("parameters", descriptor.inputSchema)
                            },
                        )
                    }
                },
            )
        }

    private fun buildHttpRequest(
        url: HttpUrl,
        credential: ProviderOAuthCredential,
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
            .header("Authorization", "Bearer ${credential.accessToken}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("OpenAI-Beta", "responses=experimental")
            .header("originator", CODEX_ORIGINATOR)
            .header("User-Agent", CODEX_USER_AGENT)
            .apply {
                val accountId =
                    credential.chatGptAccountId
                        ?: resolveCodexChatGptAccountId(credential.accessToken)
                accountId?.let { header("chatgpt-account-id", it) }
                requestId?.let { header("X-Request-Id", it) }
            }.post(body)
            .build()
    }

    private fun buildResponsesUrl(baseUrl: String): HttpUrl? {
        val parsed = baseUrl.trim().toHttpUrlOrNull() ?: return null
        val canonicalBase =
            if (parsed.host == "chatgpt.com") {
                parsed
                    .newBuilder()
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
        val errorMessage = json.extractProviderErrorMessage(rawBody)

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
                    userMessage =
                        if (statusCode == 400 && errorMessage.isNotBlank()) {
                            "OpenAI Codex rejected the request: $errorMessage"
                        } else {
                            "OpenAI Codex request failed with HTTP $statusCode."
                        },
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
        const val CODEX_ORIGINATOR = "codex_cli_rs"
        const val CODEX_USER_AGENT = "codex_cli_rs/0.0.0 (AndroidClaw Android)"
    }
}
