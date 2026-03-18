package ai.androidclaw.runtime.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProviderMessageMeta(
    val providerId: String,
    val requestId: String? = null,
    val modelId: String? = null,
    val usage: ProviderUsagePayload? = null,
)

@Serializable
data class ProviderUsagePayload(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)

private val providerMetaJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun ProviderMessageMeta.toStorageString(): String {
    return providerMetaJson.encodeToString(ProviderMessageMeta.serializer(), this)
}

fun parseProviderMessageMeta(rawValue: String?): ProviderMessageMeta? {
    if (rawValue.isNullOrBlank()) {
        return null
    }
    return runCatching {
        providerMetaJson.decodeFromString(ProviderMessageMeta.serializer(), rawValue)
    }.getOrNull()
}

fun ProviderUsage.toPayload(): ProviderUsagePayload {
    return ProviderUsagePayload(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
    )
}
