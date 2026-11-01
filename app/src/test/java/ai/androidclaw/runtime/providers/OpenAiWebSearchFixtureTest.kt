package ai.androidclaw.runtime.providers

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses the sanitized `/v1/responses` shapes saved by the 2026-11-01 hosted search probe
 * (gpt-6-luna, function tools + `{"type":"web_search"}`).
 */
class OpenAiWebSearchFixtureTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val functionNames = ProviderFunctionNameMap.from(emptyList())

    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader?.getResource("fixtures/websearch/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `non-stream response yields text, the search query and the cited source`() {
        val response =
            requireNotNull(
                parseResponsesNonEventStreamResponse(fixture("openai_responses_web_search.json"), json, functionNames, "OpenAI"),
            )

        assertTrue(response.text, response.text.contains("9.5.0"))
        assertEquals("stop", response.finishReason)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals(1, response.webSearchQueries.size)
        assertTrue(response.webSearchQueries.single().contains("Android Gradle Plugin"))
        val source = response.webSources.single()
        assertEquals("https://developer.android.com/build/releases/agp-9-5-0-release-notes?hl=en", source.url)
        assertTrue(source.title.orEmpty(), source.title.orEmpty().startsWith("Android Gradle plugin 9.5.0"))
    }

    @Test
    fun `stream events yield text deltas, both search queries and the cited source`() {
        val accumulator = ResponsesAccumulator(json, functionNames, "OpenAI")
        val deltas = StringBuilder()
        var completed: ModelResponse? = null
        fixture("openai_responses_web_search_stream.txt")
            .lineSequence()
            .filter { it.startsWith("data: ") }
            .forEach { line ->
                handleResponsesSseData(
                    data = line.removePrefix("data: "),
                    accumulator = accumulator,
                    onEvent = { event -> if (event is ModelStreamEvent.TextDelta) deltas.append(event.text) },
                    onCompleted = { completed = it },
                )
            }

        val response = requireNotNull(completed)
        assertEquals(response.text, deltas.toString())
        assertTrue(response.text, response.text.contains("9.5.0"))
        assertEquals(2, response.webSearchQueries.size)
        assertEquals(
            listOf(WebSource("https://developer.android.com/reference/tools/gradle-api", "Android Gradle plugin API reference | Android Developers")),
            response.webSources,
        )
    }

    @Test
    fun `same stream through the non-event-stream fallback parses identically`() {
        val response =
            requireNotNull(
                parseResponsesNonEventStreamResponse(fixture("openai_responses_web_search_stream.txt"), json, functionNames, "OpenAI"),
            )

        assertEquals(2, response.webSearchQueries.size)
        assertEquals(1, response.webSources.size)
    }
}
