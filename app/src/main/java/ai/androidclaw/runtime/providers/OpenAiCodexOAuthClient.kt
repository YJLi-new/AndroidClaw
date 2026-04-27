package ai.androidclaw.runtime.providers

import ai.androidclaw.data.ProviderOAuthCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Clock
import java.util.Base64

data class OpenAiCodexDeviceCodePrompt(
    val verificationUrl: String,
    val userCode: String,
    val expiresInMillis: Long,
)

interface OpenAiCodexOAuthClient {
    suspend fun loginWithDeviceCode(
        onVerification: suspend (OpenAiCodexDeviceCodePrompt) -> Unit,
        onProgress: suspend (String) -> Unit = {},
    ): ProviderOAuthCredential

    suspend fun refreshCredential(credential: ProviderOAuthCredential): ProviderOAuthCredential
}

class HttpOpenAiCodexOAuthClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val clock: Clock,
    authBaseUrl: String = OPENAI_AUTH_BASE_URL,
    private val pollDelay: suspend (Long) -> Unit = { delay(it) },
) : OpenAiCodexOAuthClient {
    private val authBaseHttpUrl = authBaseUrl.toHttpUrl()

    override suspend fun loginWithDeviceCode(
        onVerification: suspend (OpenAiCodexDeviceCodePrompt) -> Unit,
        onProgress: suspend (String) -> Unit,
    ): ProviderOAuthCredential {
        onProgress("Requesting device code...")
        val deviceCode = requestDeviceCode()
        onVerification(
            OpenAiCodexDeviceCodePrompt(
                verificationUrl = deviceCode.verificationUrl,
                userCode = deviceCode.userCode,
                expiresInMillis = OPENAI_CODEX_DEVICE_CODE_TIMEOUT_MILLIS,
            ),
        )

        onProgress("Waiting for device authorization...")
        val authorization =
            pollDeviceAuthorization(
                deviceAuthId = deviceCode.deviceAuthId,
                userCode = deviceCode.userCode,
                intervalMillis = deviceCode.intervalMillis,
            )

        onProgress("Exchanging device code...")
        return exchangeAuthorizationCode(
            authorizationCode = authorization.authorizationCode,
            codeVerifier = authorization.codeVerifier,
        )
    }

    override suspend fun refreshCredential(credential: ProviderOAuthCredential): ProviderOAuthCredential {
        val body =
            FormBody
                .Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", credential.refreshToken)
                .add("client_id", OPENAI_CODEX_CLIENT_ID)
                .build()
        val responseBody =
            executeText(
                request =
                    Request
                        .Builder()
                        .url(authUrl("/oauth/token"))
                        .header("Content-Type", FORM_URLENCODED_MEDIA_TYPE.toString())
                        .post(body)
                        .build(),
                failurePrefix = "OpenAI token refresh failed",
            )
        val payload = parseJsonObject(responseBody)
        val access = payload.stringValue("access_token")
        if (access.isNullOrBlank()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Authentication,
                userMessage = "OpenAI token refresh succeeded but did not return an access token.",
            )
        }
        val refresh = payload.stringValue("refresh_token") ?: credential.refreshToken
        val identity = resolveCodexAuthIdentity(access)
        return credential.copy(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMillis = resolveExpiresAt(payload, access),
            email = identity.email ?: credential.email,
            profileName = identity.profileName ?: credential.profileName,
            chatGptAccountId = identity.chatGptAccountId ?: credential.chatGptAccountId,
        )
    }

    private suspend fun requestDeviceCode(): RequestedDeviceCode {
        val responseBody =
            executeText(
                request =
                    Request
                        .Builder()
                        .url(authUrl("/api/accounts/deviceauth/usercode"))
                        .header("Content-Type", PROVIDER_JSON_MEDIA_TYPE.toString())
                        .post(
                            """{"client_id":"$OPENAI_CODEX_CLIENT_ID"}"""
                                .toRequestBody(PROVIDER_JSON_MEDIA_TYPE),
                        ).build(),
                failurePrefix = "OpenAI device code request failed",
            )
        val payload = parseJsonObject(responseBody)
        val deviceAuthId = payload.stringValue("device_auth_id")
        val userCode = payload.stringValue("user_code") ?: payload.stringValue("usercode")
        if (deviceAuthId.isNullOrBlank() || userCode.isNullOrBlank()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "OpenAI device code response was missing the device code or user code.",
            )
        }
        return RequestedDeviceCode(
            deviceAuthId = deviceAuthId,
            userCode = userCode,
            verificationUrl = authUrl("/codex/device").toString(),
            intervalMillis = payload.positiveSecondsMillis("interval") ?: OPENAI_CODEX_DEVICE_CODE_DEFAULT_INTERVAL_MILLIS,
        )
    }

    private suspend fun pollDeviceAuthorization(
        deviceAuthId: String,
        userCode: String,
        intervalMillis: Long,
    ): DeviceCodeAuthorization {
        val deadline = clock.millis() + OPENAI_CODEX_DEVICE_CODE_TIMEOUT_MILLIS
        while (clock.millis() < deadline) {
            val response =
                withContext(Dispatchers.IO) {
                    httpClient
                        .newCall(
                            Request
                                .Builder()
                                .url(authUrl("/api/accounts/deviceauth/token"))
                                .header("Content-Type", PROVIDER_JSON_MEDIA_TYPE.toString())
                                .post(
                                    """
                                    {
                                      "device_auth_id": "$deviceAuthId",
                                      "user_code": "$userCode"
                                    }
                                    """.trimIndent()
                                        .toRequestBody(PROVIDER_JSON_MEDIA_TYPE),
                                ).build(),
                        ).execute()
                }
            response.use {
                val bodyText = it.body?.string().orEmpty()
                if (it.isSuccessful) {
                    val payload = parseJsonObject(bodyText)
                    val authorizationCode = payload.stringValue("authorization_code")
                    val codeVerifier = payload.stringValue("code_verifier")
                    if (authorizationCode.isNullOrBlank() || codeVerifier.isNullOrBlank()) {
                        throw ModelProviderException(
                            kind = ModelProviderFailureKind.Response,
                            userMessage = "OpenAI device authorization response was missing the exchange code.",
                        )
                    }
                    return DeviceCodeAuthorization(
                        authorizationCode = authorizationCode,
                        codeVerifier = codeVerifier,
                    )
                }
                if (it.code == 403 || it.code == 404) {
                    pollDelay(resolveNextPollDelayMillis(intervalMillis, deadline))
                } else {
                    throw formatOAuthHttpFailure(
                        prefix = "OpenAI device authorization failed",
                        statusCode = it.code,
                        rawBody = bodyText,
                    )
                }
            }
        }
        throw ModelProviderException(
            kind = ModelProviderFailureKind.Timeout,
            userMessage = "OpenAI device authorization timed out after 15 minutes.",
        )
    }

    private suspend fun exchangeAuthorizationCode(
        authorizationCode: String,
        codeVerifier: String,
    ): ProviderOAuthCredential {
        val body =
            FormBody
                .Builder()
                .add("grant_type", "authorization_code")
                .add("code", authorizationCode)
                .add("redirect_uri", OPENAI_CODEX_DEVICE_CALLBACK_URL)
                .add("client_id", OPENAI_CODEX_CLIENT_ID)
                .add("code_verifier", codeVerifier)
                .build()
        val responseBody =
            executeText(
                request =
                    Request
                        .Builder()
                        .url(authUrl("/oauth/token"))
                        .header("Content-Type", FORM_URLENCODED_MEDIA_TYPE.toString())
                        .post(body)
                        .build(),
                failurePrefix = "OpenAI device token exchange failed",
            )
        val payload = parseJsonObject(responseBody)
        val access = payload.stringValue("access_token")
        val refresh = payload.stringValue("refresh_token")
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Authentication,
                userMessage = "OpenAI token exchange succeeded but did not return OAuth tokens.",
            )
        }
        val identity = resolveCodexAuthIdentity(access)
        return ProviderOAuthCredential(
            provider = OPENAI_CODEX_PROVIDER_ID,
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMillis = resolveExpiresAt(payload, access),
            email = identity.email,
            profileName = identity.profileName,
            chatGptAccountId = identity.chatGptAccountId,
        )
    }

    private suspend fun executeText(
        request: Request,
        failurePrefix: String,
    ): String =
        try {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw formatOAuthHttpFailure(
                            prefix = failurePrefix,
                            statusCode = response.code,
                            rawBody = bodyText,
                        )
                    }
                    bodyText
                }
            }
        } catch (error: IOException) {
            throw mapTransportFailure(error)
        }

    private fun authUrl(path: String): HttpUrl =
        authBaseHttpUrl
            .newBuilder()
            .encodedPath(path)
            .build()

    private fun parseJsonObject(bodyText: String): JsonObject =
        try {
            json.parseToJsonElement(bodyText).jsonObject
        } catch (error: SerializationException) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "OpenAI OAuth response was malformed.",
                details = bodyText.take(MAX_PROVIDER_ERROR_BODY_CHARS),
                cause = error,
            )
        } catch (error: IllegalArgumentException) {
            throw ModelProviderException(
                kind = ModelProviderFailureKind.Response,
                userMessage = "OpenAI OAuth response was malformed.",
                details = bodyText.take(MAX_PROVIDER_ERROR_BODY_CHARS),
                cause = error,
            )
        }

    private fun resolveExpiresAt(
        payload: JsonObject,
        accessToken: String,
    ): Long =
        payload.positiveSecondsMillis("expires_in")?.let { clock.millis() + it }
            ?: resolveCodexAccessTokenExpiry(accessToken)
            ?: clock.millis()

    private fun formatOAuthHttpFailure(
        prefix: String,
        statusCode: Int,
        rawBody: String,
    ): ModelProviderException {
        val parsed = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
        val error = parsed?.stringValue("error")
        val description = parsed?.stringValue("error_description")
        val message =
            when {
                !error.isNullOrBlank() && !description.isNullOrBlank() ->
                    "$prefix: ${sanitizeOAuthError(error)} (${sanitizeOAuthError(description)})"
                !error.isNullOrBlank() -> "$prefix: ${sanitizeOAuthError(error)}"
                rawBody.isNotBlank() -> "$prefix: HTTP $statusCode ${sanitizeOAuthError(rawBody)}"
                else -> "$prefix: HTTP $statusCode"
            }
        val kind =
            when (statusCode) {
                401, 403 -> ModelProviderFailureKind.Authentication
                else -> ModelProviderFailureKind.Server
            }
        return ModelProviderException(
            kind = kind,
            userMessage = message,
            details = rawBody.take(MAX_PROVIDER_ERROR_BODY_CHARS),
        )
    }

    private fun resolveNextPollDelayMillis(
        intervalMillis: Long,
        deadlineMillis: Long,
    ): Long {
        val remainingMillis = (deadlineMillis - clock.millis()).coerceAtLeast(0)
        return intervalMillis
            .coerceAtLeast(OPENAI_CODEX_DEVICE_CODE_MIN_INTERVAL_MILLIS)
            .coerceAtMost(remainingMillis)
    }

    private data class RequestedDeviceCode(
        val deviceAuthId: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalMillis: Long,
    )

    private data class DeviceCodeAuthorization(
        val authorizationCode: String,
        val codeVerifier: String,
    )
}

data class OpenAiCodexAuthIdentity(
    val email: String? = null,
    val profileName: String? = null,
    val chatGptAccountId: String? = null,
)

fun resolveCodexAccessTokenExpiry(accessToken: String): Long? =
    decodeCodexJwtPayload(accessToken)
        ?.longValue("exp")
        ?.takeIf { it > 0 }
        ?.times(1000)

fun resolveCodexAuthIdentity(accessToken: String): OpenAiCodexAuthIdentity {
    val payload = decodeCodexJwtPayload(accessToken) ?: return OpenAiCodexAuthIdentity()
    val auth = payload["https://api.openai.com/auth"]?.jsonObjectOrNull()
    val accountId = auth?.stringValue("chatgpt_account_id")
    val profile = payload["https://api.openai.com/profile"]?.jsonObjectOrNull()
    val email = profile?.stringValue("email")
    if (!email.isNullOrBlank()) {
        return OpenAiCodexAuthIdentity(
            email = email,
            profileName = email,
            chatGptAccountId = accountId,
        )
    }
    val subject =
        auth?.stringValue("chatgpt_account_user_id")
            ?: auth?.stringValue("chatgpt_user_id")
            ?: auth?.stringValue("user_id")
            ?: payload.stringValue("sub")
    if (subject.isNullOrBlank()) {
        return OpenAiCodexAuthIdentity(chatGptAccountId = accountId)
    }
    val encodedSubject = Base64.getUrlEncoder().withoutPadding().encodeToString(subject.toByteArray())
    return OpenAiCodexAuthIdentity(
        profileName = "id-$encodedSubject",
        chatGptAccountId = accountId,
    )
}

fun resolveCodexChatGptAccountId(accessToken: String): String? = resolveCodexAuthIdentity(accessToken).chatGptAccountId

private fun decodeCodexJwtPayload(accessToken: String): JsonObject? {
    val parts = accessToken.split(".")
    if (parts.size != 3) {
        return null
    }
    return runCatching {
        val normalizedPayload = parts[1].padEnd(parts[1].length + ((4 - parts[1].length % 4) % 4), '=')
        val decoded = Base64.getUrlDecoder().decode(normalizedPayload).decodeToString()
        Json.parseToJsonElement(decoded).jsonObject
    }.getOrNull()
}

private fun JsonObject.stringValue(name: String): String? =
    this[name]
        ?.jsonPrimitiveOrNull()
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun JsonObject.longValue(name: String): Long? {
    val primitive = this[name]?.jsonPrimitiveOrNull() ?: return null
    return primitive.longOrNull()
}

private fun JsonObject.positiveSecondsMillis(name: String): Long? =
    longValue(name)
        ?.takeIf { it > 0 }
        ?.times(1000)

private fun JsonPrimitive.longOrNull(): Long? =
    contentOrNull
        ?.trim()
        ?.toLongOrNull()

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun sanitizeOAuthError(value: String): String =
    value
        .filter { it >= ' ' && it != '\u007f' }
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_PROVIDER_ERROR_BODY_CHARS)

internal const val OPENAI_CODEX_PROVIDER_ID = "openai-codex"
internal const val OPENAI_AUTH_BASE_URL = "https://auth.openai.com"
internal const val OPENAI_CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
internal const val OPENAI_CODEX_DEVICE_CALLBACK_URL = "$OPENAI_AUTH_BASE_URL/deviceauth/callback"
private const val OPENAI_CODEX_DEVICE_CODE_TIMEOUT_MILLIS = 15 * 60 * 1000L
private const val OPENAI_CODEX_DEVICE_CODE_DEFAULT_INTERVAL_MILLIS = 5 * 1000L
private const val OPENAI_CODEX_DEVICE_CODE_MIN_INTERVAL_MILLIS = 1000L
private val FORM_URLENCODED_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
