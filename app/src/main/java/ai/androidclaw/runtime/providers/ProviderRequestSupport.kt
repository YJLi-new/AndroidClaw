package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderEndpointSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
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

internal fun OkHttpClient.withProviderTimeouts(settings: ProviderEndpointSettings): OkHttpClient =
    newBuilder()
        .callTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .connectTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

internal fun OkHttpClient.withStreamingProviderTimeouts(): OkHttpClient =
    newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

internal fun streamProviderEvents(
    buildContext: suspend () -> ProviderStreamContext,
    mapHttpFailure: (Int, String) -> ModelProviderException,
    fallbackForHttpResponse: suspend (Int, String) -> ModelResponse? = { _, _ -> null },
    fallbackForNonEventStream: suspend (String) -> ModelResponse? = { null },
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
                        val fallback = fallbackForNonEventStream(contentType)
                        if (fallback != null) {
                            complete(fallback)
                            return@use
                        }
                        throw ModelProviderException(
                            kind = ModelProviderFailureKind.Response,
                            userMessage = "Provider stream did not return an event stream.",
                            details = contentType,
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

private fun readSseDataEvents(
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
