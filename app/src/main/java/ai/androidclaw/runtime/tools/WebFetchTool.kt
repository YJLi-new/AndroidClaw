package ai.androidclaw.runtime.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal const val WEB_FETCH_MAX_BYTES = 1_000_000L
private const val WEB_FETCH_DEFAULT_CHARS = 8_000
private const val WEB_FETCH_MAX_CHARS = 20_000

internal fun webFetchToolEntry(
    baseClient: OkHttpClient?,
    allowPrivateNetwork: Boolean,
): ToolRegistry.Entry {
    val client by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        (baseClient ?: OkHttpClient())
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .apply {
                if (!allowPrivateNetwork) {
                    dns(PublicOnlyDns)
                    // IP literals and redirects skip Dns; check the address actually connected to.
                    addNetworkInterceptor { chain ->
                        val address =
                            chain
                                .connection()
                                ?.route()
                                ?.socketAddress
                                ?.address
                        if (address != null && address.isNonPublic()) {
                            throw PrivateNetworkBlockedException(chain.request().url.host)
                        }
                        chain.proceed(chain.request())
                    }
                }
            }.build()
    }
    return ToolRegistry.Entry(
        descriptor =
            ToolDescriptor(
                name = "web_fetch",
                aliases = listOf("web.fetch"),
                description =
                    "Fetch a public http(s) page and return readable text (HTML is converted). " +
                        "Local and private network addresses are blocked.",
                riskTier = ToolRiskTier.ReadOnly,
                allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                arguments =
                    listOf(
                        stringArg("url", "http or https URL.", required = true),
                        intArg("maxChars", "Max text characters, default 8000, max 20000."),
                    ),
            ),
    ) { _, arguments ->
        val rawUrl = arguments.requireText("url", "web_fetch")
        val url =
            rawUrl.toHttpUrlOrNull()
                ?: return@Entry toolFailure("INVALID_URL", "web_fetch only accepts http:// or https:// URLs.")
        if (!allowPrivateNetwork && url.host.ipLiteralOrNull()?.isNonPublic() == true) {
            return@Entry toolFailure("FETCH_BLOCKED_OR_UNKNOWN_HOST", PrivateNetworkBlockedException(url.host).message.orEmpty())
        }
        val maxChars = arguments.boundedInt("maxChars", WEB_FETCH_DEFAULT_CHARS, 200, WEB_FETCH_MAX_CHARS)
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", "AndroidClaw/0.1 (+web_fetch)")
                .header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.5")
                .get()
                .build()
        val call = client.newCall(request)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    val body = response.body ?: return@withContext toolFailure("FETCH_FAILED", "Empty response from ${url.host}.")
                    val contentType = body.contentType()
                    val mediaType = contentType?.let { "${it.type}/${it.subtype}" }?.lowercase()
                    if (mediaType != null && !mediaType.isTextLike()) {
                        return@withContext toolFailure(
                            "UNSUPPORTED_CONTENT",
                            "web_fetch reads text pages only; ${response.request.url} returned $mediaType.",
                        )
                    }
                    val source = body.source()
                    source.request(WEB_FETCH_MAX_BYTES)
                    val bytes = source.buffer.size.coerceAtMost(WEB_FETCH_MAX_BYTES)
                    val charset = contentType?.charset() ?: Charsets.UTF_8
                    val raw = source.buffer.readString(bytes, charset)
                    val isHtml = mediaType?.contains("html") ?: raw.trimStart().startsWith("<")
                    val title = if (isHtml) htmlTitle(raw) else null
                    val text = if (isHtml) htmlToText(raw) else raw.trim()
                    val clipped = text.take(maxChars)
                    if (!response.isSuccessful) {
                        return@withContext toolFailure(
                            "HTTP_ERROR",
                            "HTTP ${response.code} from ${response.request.url}: ${clipped.singleLine().clip(300)}",
                            buildJsonObject { put("status", response.code) },
                        )
                    }
                    toolSuccess(
                        summary =
                            buildString {
                                append("Fetched ").append(response.request.url)
                                title?.let { append(" — ").append(it.clip(160)) }
                                append(" (").append(text.length).append(" chars")
                                if (text.length > clipped.length) append(", showing first ").append(clipped.length)
                                append(").")
                            },
                        payload =
                            buildJsonObject {
                                put("url", response.request.url.toString())
                                put("status", response.code)
                                put("text", clipped)
                            },
                    )
                }
            }
        } catch (error: PrivateNetworkBlockedException) {
            toolFailure("FETCH_BLOCKED_OR_UNKNOWN_HOST", error.message ?: "Blocked ${url.host}.")
        } catch (error: UnknownHostException) {
            toolFailure("FETCH_BLOCKED_OR_UNKNOWN_HOST", error.message ?: "Unknown host ${url.host}.")
        } catch (error: IOException) {
            toolFailure("FETCH_FAILED", "Could not fetch ${url.host}: ${error.message ?: error.javaClass.simpleName}.")
        } finally {
            cancelHandle?.dispose()
        }
    }
}

private fun String.isTextLike(): Boolean =
    startsWith("text/") ||
        this == "application/json" ||
        this == "application/xml" ||
        this == "application/xhtml+xml" ||
        this == "application/javascript" ||
        endsWith("+json") ||
        endsWith("+xml")

private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/** Parses an IP literal without any DNS lookup; returns null for host names. */
private fun String.ipLiteralOrNull(): InetAddress? = if (IPV4_LITERAL.matches(this) || contains(':')) runCatching { InetAddress.getByName(this) }.getOrNull() else null

internal class PrivateNetworkBlockedException(
    host: String,
) : IOException("$host is a local or private network address; web_fetch only reads public sites.")

/** Rejects hosts that resolve to loopback, private, link-local or carrier-grade NAT addresses. */
internal object PublicOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = Dns.SYSTEM.lookup(hostname)
        val public = resolved.filterNot(InetAddress::isNonPublic)
        if (public.isEmpty()) {
            throw UnknownHostException("$hostname is a local or private network address; web_fetch only reads public sites.")
        }
        return public
    }
}

internal fun InetAddress.isNonPublic(): Boolean {
    if (isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress || isAnyLocalAddress || isMulticastAddress) {
        return true
    }
    val bytes = address
    if (this is Inet6Address) {
        return (bytes[0].toInt() and 0xFE) == 0xFC // fc00::/7 unique local
    }
    val first = bytes[0].toInt() and 0xFF
    val second = bytes[1].toInt() and 0xFF
    return first == 100 && second in 64..127 // 100.64.0.0/10 carrier-grade NAT
}

private val HTML_DROP_BLOCKS =
    Regex("(?is)<(script|style|noscript|svg|template|head)\\b[^>]*>.*?</\\1\\s*>")
private val HTML_COMMENT = Regex("(?s)<!--.*?-->")
private val HTML_TITLE = Regex("(?is)<title\\b[^>]*>(.*?)</title\\s*>")
private val HTML_LIST_ITEM = Regex("(?i)<li\\b[^>]*>")
private val HTML_BLOCK_BREAK =
    Regex("(?i)</?(p|div|br|h[1-6]|tr|section|article|header|footer|main|nav|aside|ul|ol|table|pre|blockquote|hr|dd|dt|figure)\\b[^>]*>")
private val HTML_TAG = Regex("(?s)<[^>]+>")
private val HTML_NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
private val HTML_NAMED_ENTITY = Regex("&(amp|lt|gt|quot|apos|nbsp|#39|mdash|ndash|hellip|copy|reg|trade);")
private val HORIZONTAL_SPACE = Regex("[ \\t\\u00A0\\f\\r]+")
private val MANY_NEWLINES = Regex("\\n{3,}")

internal fun htmlTitle(html: String): String? =
    HTML_TITLE
        .find(html)
        ?.groupValues
        ?.get(1)
        ?.let(::decodeHtmlEntities)
        ?.singleLine()
        ?.takeIf(String::isNotBlank)

internal fun htmlToText(html: String): String {
    val withoutNoise = html.replace(HTML_COMMENT, " ").replace(HTML_DROP_BLOCKS, " ")
    val structured =
        withoutNoise
            .replace(HTML_LIST_ITEM, "\n- ")
            .replace(HTML_BLOCK_BREAK, "\n")
            .replace(HTML_TAG, "")
    return decodeHtmlEntities(structured)
        .lineSequence()
        .map { line -> line.replace(HORIZONTAL_SPACE, " ").trim() }
        .joinToString("\n")
        .replace(MANY_NEWLINES, "\n\n")
        .trim()
}

internal fun decodeHtmlEntities(text: String): String =
    text
        .replace(HTML_NUMERIC_ENTITY) { match ->
            val value = match.groupValues[1]
            val codePoint =
                if (value.startsWith("x", ignoreCase = true)) {
                    value.substring(1).toIntOrNull(16)
                } else {
                    value.toIntOrNull()
                }
            codePoint
                ?.takeIf { Character.isValidCodePoint(it) }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }.replace(HTML_NAMED_ENTITY) { match ->
            when (match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos", "#39" -> "'"
                "nbsp" -> " "
                "mdash" -> "—"
                "ndash" -> "–"
                "hellip" -> "…"
                "copy" -> "©"
                "reg" -> "®"
                "trade" -> "™"
                else -> match.value
            }
        }
