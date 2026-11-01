package ai.androidclaw.runtime.tools

import ai.androidclaw.data.OPENAI_DEFAULT_BASE_URL
import ai.androidclaw.data.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSearchModeTest {
    private fun mode(
        providerType: ProviderType,
        baseUrl: String = providerType.defaultBaseUrl,
        enabled: Boolean = true,
        hasBraveKey: Boolean = false,
    ): WebSearchResolution = resolveWebSearchMode(enabled, hasBraveKey, providerType, baseUrl)

    @Test
    fun `brave key selects the typed tool for every provider`() {
        ProviderType.entries.forEach { providerType ->
            assertEquals(providerType.name, WebSearchResolution.Typed, mode(providerType, hasBraveKey = true))
        }
    }

    @Test
    fun `off in settings wins over a brave key and hosted search`() {
        val off = WebSearchResolution.off(WebSearchOffReason.DisabledInSettings)
        assertEquals(off, mode(ProviderType.OpenAi, enabled = false, hasBraveKey = true))
        assertEquals(off, mode(ProviderType.Anthropic, enabled = false))
    }

    @Test
    fun `hosted search for OpenAI on api openai com and Claude`() {
        assertEquals(WebSearchResolution.Hosted, mode(ProviderType.OpenAi))
        assertEquals(WebSearchResolution.Hosted, mode(ProviderType.OpenAiCompatible, baseUrl = OPENAI_DEFAULT_BASE_URL))
        assertEquals(WebSearchResolution.Hosted, mode(ProviderType.Anthropic))
    }

    @Test
    fun `no hosted search for codex, other hosts, presets and the fake provider`() {
        val off = WebSearchResolution.off(WebSearchOffReason.ProviderHasNoHostedSearch)
        assertEquals(off, mode(ProviderType.OpenAiCodex))
        assertEquals(off, mode(ProviderType.OpenAiCompatible, baseUrl = "https://openrouter.ai/api/v1"))
        assertEquals(off, mode(ProviderType.OpenAi, baseUrl = "http://10.0.2.2:11434/v1"))
        assertEquals(off, mode(ProviderType.OpenAi, baseUrl = "not a url"))
        listOf(ProviderType.Gemini, ProviderType.DeepSeek, ProviderType.Glm, ProviderType.Kimi, ProviderType.MiniMax, ProviderType.Fake)
            .forEach { assertEquals(it.name, off, mode(it)) }
    }
}
