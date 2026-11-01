package ai.androidclaw.runtime.tools

import ai.androidclaw.runtime.providers.ModelRunMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Brave Search contract (no Brave key on this host): request shape, parsing, bounds and error codes. */
class BraveWebSearchToolTest {
    private lateinit var server: MockWebServer
    private var apiKey: String? = "brave-test-key"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun registry(): ToolRegistry =
        ToolRegistry(
            tools =
                listOf(
                    webSearchToolEntry(
                        client = BraveSearchClient(baseUrl = server.url("/")),
                        readApiKey = { apiKey },
                    ),
                ),
        )

    private suspend fun run(
        arguments: JsonObject,
        origin: ToolInvocationOrigin = ToolInvocationOrigin.Model,
    ): ToolExecutionResult =
        registry().execute(
            context =
                ToolExecutionContext(
                    sessionId = "s1",
                    taskRunId = null,
                    origin = origin,
                    runMode = if (origin == ToolInvocationOrigin.ScheduledModel) ModelRunMode.Scheduled else ModelRunMode.Interactive,
                    requestedName = WEB_SEARCH_TOOL_NAME,
                    canonicalName = WEB_SEARCH_TOOL_NAME,
                    requestId = "r1",
                ),
            arguments = arguments,
        )

    private fun args(vararg pairs: Pair<String, Any>): JsonObject =
        buildJsonObject {
            pairs.forEach { (key, value) ->
                when (value) {
                    is Int -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }

    private fun braveBody(vararg results: String): String = """{"type":"search","web":{"type":"search","results":[${results.joinToString(",")}]}}"""

    private fun result(
        index: Int,
        description: String = "Snippet <strong>$index</strong> &amp; more",
        path: String = "$index",
    ): String = """{"title":"Result <b>$index</b>","url":"https://example.com/$path","description":"$description","age":"2 days ago"}"""

    @Test
    fun `sends the brave request and returns title url and snippet`() =
        runTest {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(braveBody(result(1), result(2))))

            val outcome =
                run(
                    buildJsonObject {
                        put("query", "android gradle plugin")
                        put("count", 3)
                        put("freshness", "week")
                    },
                )

            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/res/v1/web/search", recorded.requestUrl!!.encodedPath)
            assertEquals("android gradle plugin", recorded.requestUrl!!.queryParameter("q"))
            assertEquals("3", recorded.requestUrl!!.queryParameter("count"))
            assertEquals("pw", recorded.requestUrl!!.queryParameter("freshness"))
            assertEquals("brave-test-key", recorded.getHeader("X-Subscription-Token"))
            assertEquals("application/json", recorded.getHeader("Accept"))
            assertTrue(outcome.success)
            val results = outcome.payload["results"]!!.jsonArray
            assertEquals(2, results.size)
            val first = results[0].jsonObject
            assertEquals("Result 1", first["title"]!!.jsonPrimitive.content)
            assertEquals("https://example.com/1", first["url"]!!.jsonPrimitive.content)
            assertEquals("Snippet 1 & more", first["snippet"]!!.jsonPrimitive.content)
            assertEquals("2 days ago", first["age"]!!.jsonPrimitive.content)
            assertTrue(outcome.summary, outcome.summary.contains("2 web results"))
        }

    @Test
    fun `count is clamped to ten and freshness is omitted by default`() =
        runTest {
            server.enqueue(MockResponse().setBody(braveBody()))

            val outcome = run(args("query" to "q", "count" to 50))

            val url = server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!
            assertEquals("10", url.queryParameter("count"))
            assertNull(url.queryParameter("freshness"))
            assertTrue(outcome.success)
            assertTrue(outcome.summary.startsWith("No web results"))
        }

    @Test
    fun `result text stays within 8 KB`() =
        runTest {
            val long = "x".repeat(2_000)
            // Snippets clip to 400 chars and URLs to 500, so ten long results exceed 8 KB.
            server.enqueue(
                MockResponse().setBody(
                    braveBody(*(1..10).map { result(it, description = long, path = "$it/" + "p".repeat(470)) }.toTypedArray()),
                ),
            )

            val outcome = run(args("query" to "big", "count" to 10))

            assertTrue(outcome.success)
            val size = outcome.toModelContent().toByteArray(Charsets.UTF_8).size
            assertTrue("size=$size", size <= WEB_SEARCH_RESULT_MAX_BYTES)
            val kept = outcome.payload["results"]!!.jsonArray.size
            assertTrue("kept=$kept", kept in 1..9)
        }

    @Test
    fun `http errors map to HTTP_ERROR with the status`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"type":"ErrorResponse","error":{"code":"SUBSCRIPTION_TOKEN_INVALID","detail":"The provided subscription token is invalid.","status":401}}"""),
            )
            server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))

            val unauthorized = run(buildJsonObject { put("query", "q") })
            val limited = run(buildJsonObject { put("query", "q") })

            assertFalse(unauthorized.success)
            assertEquals("HTTP_ERROR", unauthorized.errorCode)
            assertEquals(401, unauthorized.payload["status"]!!.jsonPrimitive.int)
            assertTrue(unauthorized.summary, unauthorized.summary.contains("API key was rejected"))
            assertTrue(unauthorized.summary, unauthorized.summary.contains("subscription token is invalid"))
            assertEquals("HTTP_ERROR", limited.errorCode)
            assertTrue(limited.summary, limited.summary.contains("rate limit"))
        }

    @Test
    fun `transport failure maps to FETCH_FAILED and malformed json too`() =
        runTest {
            server.enqueue(MockResponse().setBody("not json"))
            val malformed = run(buildJsonObject { put("query", "q") })
            server.shutdown()
            val unreachable = run(buildJsonObject { put("query", "q") })

            assertEquals("FETCH_FAILED", malformed.errorCode)
            assertEquals("FETCH_FAILED", unreachable.errorCode)
        }

    @Test
    fun `missing key is a config failure and makes no request`() =
        runTest {
            apiKey = null

            val outcome = run(buildJsonObject { put("query", "q") })

            assertEquals("DISABLED_BY_CONFIG", outcome.errorCode)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `invalid freshness and missing query are argument errors`() =
        runTest {
            val badFreshness = run(args("query" to "q", "freshness" to "yesterday"))
            val noQuery = run(buildJsonObject { put("count", 2) })

            assertEquals("INVALID_ARGUMENTS", badFreshness.errorCode)
            assertEquals("INVALID_ARGUMENTS", noQuery.errorCode)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `allowed in scheduled runs and read only`() =
        runTest {
            server.enqueue(MockResponse().setBody(braveBody(result(1))))

            val outcome = run(buildJsonObject { put("query", "q") }, origin = ToolInvocationOrigin.ScheduledModel)
            val descriptor = registry().descriptorsFor(ToolInvocationOrigin.ScheduledModel, ModelRunMode.Scheduled).single()

            assertTrue(outcome.success)
            assertEquals(WEB_SEARCH_TOOL_NAME, descriptor.name)
            assertEquals(ToolRiskTier.ReadOnly, descriptor.riskTier)
            assertEquals(listOf("query", "count", "freshness"), descriptor.arguments.map { it.name })
        }

    @Test
    fun `freshness accepts OpenClaw values and date ranges`() {
        assertEquals("pd", normalizeWebSearchFreshness("pd"))
        assertEquals("pm", normalizeWebSearchFreshness("Month"))
        assertEquals("2026-01-01to2026-02-01", normalizeWebSearchFreshness("2026-01-01to2026-02-01"))
        assertNull(normalizeWebSearchFreshness(" "))
    }
}
