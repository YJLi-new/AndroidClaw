package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.normalizeProviderTimeoutSeconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal fun invalidEndpointFailure(baseUrl: String): ModelProviderException =
    ModelProviderException(
        kind = ModelProviderFailureKind.InvalidEndpoint,
        userMessage = "Provider base URL is invalid.",
        details = "Configured base URL: $baseUrl",
    )

internal fun offlineFailure(): ModelProviderException =
    ModelProviderException(
        kind = ModelProviderFailureKind.Offline,
        userMessage = "No active network connection. Remote providers cannot be reached right now.",
    )

internal fun timeoutFailure(
    settings: ProviderEndpointSettings,
    error: Throwable,
): ModelProviderException =
    ModelProviderException(
        kind = ModelProviderFailureKind.Timeout,
        userMessage = "Provider request timed out.",
        details = "Timed out after ${normalizeProviderTimeoutSeconds(settings.timeoutSeconds)} seconds.",
        cause = error,
    )

internal fun mapTransportFailure(error: IOException): ModelProviderException =
    when (error) {
        is UnknownHostException ->
            ModelProviderException(
                kind = ModelProviderFailureKind.Network,
                userMessage = "Provider host could not be resolved. Check the base URL and DNS.",
                details = error.message,
                cause = error,
            )

        is ConnectException, is NoRouteToHostException ->
            ModelProviderException(
                kind = ModelProviderFailureKind.Network,
                userMessage = "Could not connect to the provider endpoint.",
                details = error.message,
                cause = error,
            )

        is SSLException ->
            ModelProviderException(
                kind = ModelProviderFailureKind.Network,
                userMessage = "TLS negotiation with the provider failed.",
                details = error.message,
                cause = error,
            )

        is SocketException ->
            ModelProviderException(
                kind = ModelProviderFailureKind.Network,
                userMessage = "Provider connection closed unexpectedly.",
                details = error.message,
                cause = error,
            )

        else ->
            ModelProviderException(
                kind = ModelProviderFailureKind.Network,
                userMessage = "Provider request failed due to a network error.",
                details = error.message,
                cause = error,
            )
    }

internal fun streamInterruptedFailure(
    details: String? = null,
    cause: Throwable? = null,
): ModelProviderException =
    ModelProviderException(
        kind = ModelProviderFailureKind.StreamInterrupted,
        userMessage = "Provider stream was interrupted before completion.",
        details = details,
        cause = cause,
    )

internal fun Json.extractProviderErrorMessage(rawBody: String): String =
    runCatching {
        parseToJsonElement(rawBody)
            .jsonObject
            .providerErrorMessage()
    }.getOrNull()
        .orEmpty()
        .sanitizeProviderErrorMessage()

internal fun String.sanitizeProviderErrorMessage(): String =
    replace(Regex("\\s+"), " ")
        .filter { it >= ' ' && it != '\u007f' }
        .trim()
        .take(MAX_PROVIDER_ERROR_BODY_CHARS)

private fun JsonObject.providerErrorMessage(): String? {
    val error = this["error"]
    return when (error) {
        is JsonObject -> error.firstStringValue("message", "detail", "description", "code", "type")
        is JsonPrimitive -> error.contentOrNull
        else -> null
    } ?: firstStringValue("detail", "message", "error_description", "description")
}

private fun JsonObject.firstStringValue(vararg keys: String): String? =
    keys
        .asSequence()
        .mapNotNull(::stringValue)
        .firstOrNull()

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }
