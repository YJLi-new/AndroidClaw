package ai.androidclaw.runtime.tools

import ai.androidclaw.runtime.providers.WebSearchCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal const val WEB_SEARCH_DEFAULT_COUNT = 5
internal const val WEB_SEARCH_MAX_COUNT = 10
internal const val WEB_SEARCH_RESULT_MAX_BYTES = 8 * 1024
private const val WEB_SEARCH_QUERY_MAX_CHARS = 400
private const val WEB_SEARCH_TITLE_MAX_CHARS = 200
private const val WEB_SEARCH_URL_MAX_CHARS = 500
private const val WEB_SEARCH_SNIPPET_MAX_CHARS = 400
private const val BRAVE_RESPONSE_MAX_BYTES = 512_000L
internal val BRAVE_SEARCH_DEFAULT_BASE_URL: HttpUrl = "https://api.search.brave.com/".toHttpUrl()
private val FRESHNESS_RANGE = Regex("""^\d{4}-\d{2}-\d{2}to\d{4}-\d{2}-\d{2}$""")

data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val age: String? = null,
)

sealed interface BraveSearchOutcome {
    data class Success(
        val hits: List<WebSearchHit>,
    ) : BraveSearchOutcome

    data class Failure(
        val errorCode: String,
        val message: String,
        val httpStatus: Int? = null,
    ) : BraveSearchOutcome
}

/** Brave Search API `res/v1/web/search` client. Shared by the typed tool and the Settings test button. */
class BraveSearchClient(
    baseClient: OkHttpClient? = null,
    private val baseUrl: HttpUrl = BRAVE_SEARCH_DEFAULT_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        (baseClient ?: OkHttpClient())
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun search(
        apiKey: String,
        query: String,
        count: Int = WEB_SEARCH_DEFAULT_COUNT,
        freshness: String? = null,
    ): BraveSearchOutcome {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegments("res/v1/web/search")
                .addQueryParameter("q", query)
                .addQueryParameter("count", count.coerceIn(1, WEB_SEARCH_MAX_COUNT).toString())
                .apply { freshness?.let { addQueryParameter("freshness", it) } }
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .header("User-Agent", "AndroidClaw/0.3 (+web_search)")
                .get()
                .build()
        val call = client.newCall(request)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        return try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    val source = response.body?.source()
                    source?.request(BRAVE_RESPONSE_MAX_BYTES)
                    val raw = source?.buffer?.let { it.readUtf8(it.size.coerceAtMost(BRAVE_RESPONSE_MAX_BYTES)) }.orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext BraveSearchOutcome.Failure(
                            errorCode = "HTTP_ERROR",
                            message = braveHttpErrorMessage(response.code, raw),
                            httpStatus = response.code,
                        )
                    }
                    parseBraveResults(raw)
                }
            }
        } catch (error: UnknownHostException) {
            BraveSearchOutcome.Failure("FETCH_BLOCKED_OR_UNKNOWN_HOST", error.message ?: "Unknown host ${url.host}.")
        } catch (error: IOException) {
            BraveSearchOutcome.Failure(
                "FETCH_FAILED",
                "Could not reach ${url.host}: ${error.message ?: error.javaClass.simpleName}.",
            )
        } finally {
            cancelHandle?.dispose()
        }
    }

    private fun parseBraveResults(raw: String): BraveSearchOutcome {
        val root =
            runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return BraveSearchOutcome.Failure("FETCH_FAILED", "Brave Search returned malformed JSON.")
        val results = ((root["web"] as? JsonObject)?.get("results") as? JsonArray).orEmpty()
        val hits =
            results.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = item.text("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
                WebSearchHit(
                    title =
                        item
                            .text("title")
                            ?.let(::cleanSnippet)
                            .orEmpty()
                            .ifBlank { url }
                            .clip(WEB_SEARCH_TITLE_MAX_CHARS),
                    url = url.take(WEB_SEARCH_URL_MAX_CHARS),
                    snippet =
                        item
                            .text("description")
                            ?.let(::cleanSnippet)
                            .orEmpty()
                            .clip(WEB_SEARCH_SNIPPET_MAX_CHARS),
                    age = item.text("age")?.clip(40),
                )
            }
        return BraveSearchOutcome.Success(hits.distinctBy { it.url })
    }

    private fun braveHttpErrorMessage(
        status: Int,
        raw: String,
    ): String {
        val detail =
            runCatching {
                val error = (json.parseToJsonElement(raw) as? JsonObject)?.get("error") as? JsonObject
                error?.text("detail") ?: error?.text("code")
            }.getOrNull()
        val hint =
            when (status) {
                401, 403 -> "the Brave API key was rejected"
                422 -> "Brave rejected the query"
                429 -> "the Brave rate limit or quota was reached"
                else -> "Brave Search failed"
            }
        return buildString {
            append("HTTP ").append(status).append(" from Brave Search: ").append(hint)
            detail?.takeIf { it.isNotBlank() }?.let { append(" (").append(it.singleLine().clip(200)).append(')') }
            append('.')
        }
    }
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** Brave snippets carry `<strong>` highlights and entities. */
private fun cleanSnippet(value: String): String = htmlToText(value).singleLine()

/** Maps OpenClaw-style freshness values; returns null for an absent value, throws for an invalid one. */
internal fun normalizeWebSearchFreshness(value: String?): String? {
    val trimmed = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when (trimmed) {
        "pd", "day", "24h" -> "pd"
        "pw", "week" -> "pw"
        "pm", "month" -> "pm"
        "py", "year" -> "py"
        else ->
            trimmed.takeIf(FRESHNESS_RANGE::matches)
                ?: throw IllegalArgumentException(
                    "web_search freshness must be pd, pw, pm, py or YYYY-MM-DDtoYYYY-MM-DD.",
                )
    }
}

internal fun webSearchToolEntry(
    client: BraveSearchClient,
    readApiKey: suspend () -> String?,
): ToolRegistry.Entry =
    ToolRegistry.Entry(
        descriptor =
            ToolDescriptor(
                name = WEB_SEARCH_TOOL_NAME,
                description =
                    "Search the web (Brave Search). Returns title, url and snippet per result; " +
                        "use web_fetch to read a page.",
                riskTier = ToolRiskTier.ReadOnly,
                allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                arguments =
                    listOf(
                        stringArg("query", "Search query.", required = true),
                        intArg("count", "Results, 1-10, default 5."),
                        stringArg("freshness", "pd, pw, pm, py, or YYYY-MM-DDtoYYYY-MM-DD."),
                    ),
            ),
    ) { _, arguments ->
        val query = arguments.requireText("query", WEB_SEARCH_TOOL_NAME).singleLine().take(WEB_SEARCH_QUERY_MAX_CHARS)
        val count = arguments.boundedInt("count", WEB_SEARCH_DEFAULT_COUNT, 1, WEB_SEARCH_MAX_COUNT)
        val freshness = normalizeWebSearchFreshness(arguments.optionalText("freshness"))
        val apiKey =
            readApiKey()?.takeIf { it.isNotBlank() }
                ?: return@Entry toolFailure(
                    "DISABLED_BY_CONFIG",
                    "web_search needs a Brave Search API key in Settings → Web search.",
                )
        when (val outcome = client.search(apiKey, query, count, freshness)) {
            is BraveSearchOutcome.Failure ->
                toolFailure(
                    outcome.errorCode,
                    outcome.message,
                    buildJsonObject { outcome.httpStatus?.let { put("status", it) } },
                )

            is BraveSearchOutcome.Success -> webSearchSuccess(query, outcome.hits)
        }
    }

/** Feeds a successful typed `web_search` result into the turn's sources (same card and row as hosted search). */
internal fun WebSearchCollector.addTypedWebSearchResult(
    toolName: String,
    result: ToolExecutionResult,
) {
    if (toolName != WEB_SEARCH_TOOL_NAME || !result.success) return
    addQuery(result.payload.optionalText("query"))
    (result.payload["results"] as? JsonArray).orEmpty().forEach { element ->
        val hit = element as? JsonObject ?: return@forEach
        addSource(hit.optionalText("url"), hit.optionalText("title"))
    }
}

/** Builds the tool result, dropping trailing results until the model-facing text fits 8 KB. */
internal fun webSearchSuccess(
    query: String,
    hits: List<WebSearchHit>,
): ToolExecutionResult {
    var kept = hits
    while (true) {
        val result =
            toolSuccess(
                summary =
                    if (kept.isEmpty()) {
                        "No web results for \"${query.clip(120)}\"."
                    } else {
                        "Found ${kept.size} web results for \"${query.clip(120)}\"."
                    },
                payload =
                    buildJsonObject {
                        put("query", query)
                        put(
                            "results",
                            buildJsonArray {
                                kept.forEach { hit ->
                                    add(
                                        buildJsonObject {
                                            put("title", hit.title)
                                            put("url", hit.url)
                                            put("snippet", hit.snippet)
                                            hit.age?.let { put("age", it) }
                                        },
                                    )
                                }
                            },
                        )
                    },
            )
        if (kept.isEmpty() || result.toModelContent().toByteArray(Charsets.UTF_8).size <= WEB_SEARCH_RESULT_MAX_BYTES) {
            return result
        }
        kept = kept.dropLast(1)
    }
}
