package ai.androidclaw.runtime.providers

import ai.androidclaw.data.repository.MESSAGE_PROVIDER_META_MAX_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchMetaTest {
    @Test
    fun `collector dedupes by url without tracking parameters and fragments, at most 8`() {
        val collector = WebSearchCollector()
        collector.addSource("https://a.example/page?utm_source=openai#top", "A")
        collector.addSource("https://a.example/page", "A again")
        collector.addSource("ftp://not-web.example/file", "ftp")
        collector.addSource("not a url", "bad")
        (1..20).forEach { collector.addSource("https://b.example/$it?q=$it", "  B   $it ") }

        val sources = collector.sources()
        assertEquals(WEB_SOURCES_MAX, sources.size)
        assertEquals(WebSource("https://a.example/page", "A"), sources.first())
        assertEquals(WebSource("https://b.example/1?q=1", "B 1"), sources[1])
    }

    @Test
    fun `collector keeps at most three distinct queries`() {
        val collector = WebSearchCollector()
        listOf("one", " one ", "two", "", "three", "four").forEach(collector::addQuery)

        assertEquals(listOf("one", "two", "three"), collector.queries())
    }

    @Test
    fun `meta round-trips sources and omits empty search fields`() {
        val meta =
            ProviderMessageMeta(
                providerId = "openai",
                webSearchQueries = listOf("agp latest"),
                sources = listOf(WebSource("https://developer.android.com/agp", "AGP")),
            )

        assertEquals(meta, parseProviderMessageMeta(meta.toStorageString()))
        val plain = ProviderMessageMeta(providerId = "openai").toStorageString()
        assertTrue(plain, !plain.contains("sources") && !plain.contains("webSearchQueries"))
    }

    @Test
    fun `oversized meta drops trailing sources so it still fits and parses`() {
        val sources = (1..8).map { WebSource("https://example.com/" + "p".repeat(280) + it, "t".repeat(120)) }
        val meta =
            ProviderMessageMeta(
                providerId = "anthropic",
                requestId = "r".repeat(200),
                webSearchQueries = listOf("q".repeat(160), "w".repeat(160), "e".repeat(160)),
                sources = sources,
            )

        val stored = meta.toStorageString()

        assertTrue("length=${stored.length}", stored.length <= MESSAGE_PROVIDER_META_MAX_CHARS)
        val parsed = requireNotNull(parseProviderMessageMeta(stored))
        assertTrue(parsed.sources.size in 1 until sources.size)
        assertEquals(sources.take(parsed.sources.size), parsed.sources)
        assertEquals(3, parsed.webSearchQueries.size)
    }
}
