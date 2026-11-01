package ai.androidclaw.runtime.providers

import ai.androidclaw.data.repository.MESSAGE_PROVIDER_META_MAX_CHARS
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class ProviderMessageMeta(
    val providerId: String,
    val requestId: String? = null,
    val modelId: String? = null,
    val usage: ProviderUsagePayload? = null,
    /** Queries the turn searched for (hosted search or the typed web_search tool). */
    val webSearchQueries: List<String> = emptyList(),
    /** Web sources behind the reply, deduplicated by URL, at most [WEB_SOURCES_MAX]. */
    val sources: List<WebSource> = emptyList(),
)

@Serializable
data class ProviderUsagePayload(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)

@Serializable
data class WebSource(
    val url: String,
    val title: String? = null,
)

internal const val WEB_SOURCES_MAX = 8
internal const val WEB_SEARCH_QUERIES_MAX = 3
private const val WEB_SOURCE_URL_MAX_CHARS = 300
private const val WEB_SOURCE_TITLE_MAX_CHARS = 120
private const val WEB_SEARCH_QUERY_META_MAX_CHARS = 160

private val providerMetaJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/**
 * Encodes the meta for the messages table. The column is bounded, and a clipped JSON string would
 * not parse, so sources (then queries) are dropped from the end until the encoded meta fits.
 */
fun ProviderMessageMeta.toStorageString(): String {
    var meta = this
    while (true) {
        val encoded = providerMetaJson.encodeToString(ProviderMessageMeta.serializer(), meta)
        if (encoded.length <= MESSAGE_PROVIDER_META_MAX_CHARS) {
            return encoded
        }
        meta =
            when {
                meta.sources.isNotEmpty() -> meta.copy(sources = meta.sources.dropLast(1))
                meta.webSearchQueries.isNotEmpty() -> meta.copy(webSearchQueries = meta.webSearchQueries.dropLast(1))
                else -> return encoded
            }
    }
}

fun parseProviderMessageMeta(rawValue: String?): ProviderMessageMeta? {
    if (rawValue.isNullOrBlank()) {
        return null
    }
    return runCatching {
        providerMetaJson.decodeFromString(ProviderMessageMeta.serializer(), rawValue)
    }.getOrNull()
}

fun ProviderUsage.toPayload(): ProviderUsagePayload =
    ProviderUsagePayload(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
    )

/**
 * Collects web search queries and sources across the rounds of one turn: bounded, http(s) only,
 * deduplicated by URL (tracking parameters and fragments removed).
 */
class WebSearchCollector {
    private val queries = LinkedHashSet<String>()
    private val sources = LinkedHashMap<String, WebSource>()

    fun addQuery(query: String?) {
        val normalized = query?.trim()?.replace(WHITESPACE, " ")?.take(WEB_SEARCH_QUERY_META_MAX_CHARS) ?: return
        if (normalized.isNotEmpty() && queries.size < WEB_SEARCH_QUERIES_MAX) {
            queries += normalized
        }
    }

    fun addSource(
        url: String?,
        title: String?,
    ) {
        val cleanUrl = url?.let(::normalizeWebSourceUrl) ?: return
        if (sources.size >= WEB_SOURCES_MAX || sources.containsKey(cleanUrl)) {
            return
        }
        sources[cleanUrl] =
            WebSource(
                url = cleanUrl,
                title =
                    title
                        ?.trim()
                        ?.replace(WHITESPACE, " ")
                        ?.take(WEB_SOURCE_TITLE_MAX_CHARS)
                        ?.takeIf { it.isNotEmpty() },
            )
    }

    fun addAll(
        queries: List<String>,
        sources: List<WebSource>,
    ) {
        queries.forEach(::addQuery)
        sources.forEach { addSource(it.url, it.title) }
    }

    fun queries(): List<String> = queries.toList()

    fun sources(): List<WebSource> = sources.values.toList()

    fun isEmpty(): Boolean = queries.isEmpty() && sources.isEmpty()
}

// Page titles often pad separators with no-break spaces ("Docs  |  Android Developers").
private val WHITESPACE = Regex("[\\s\\u00A0\\u2007\\u202F]+")
private val TRACKING_PARAMETER = Regex("^(utm_[a-z]+|ref_src)$", RegexOption.IGNORE_CASE)

internal fun normalizeWebSourceUrl(rawUrl: String): String? {
    val url = rawUrl.trim().toHttpUrlOrNull() ?: return null
    val builder = url.newBuilder().fragment(null)
    url.queryParameterNames
        .filter(TRACKING_PARAMETER::matches)
        .forEach(builder::removeAllQueryParameters)
    return builder
        .build()
        .toString()
        .takeIf { it.length <= WEB_SOURCE_URL_MAX_CHARS }
}
