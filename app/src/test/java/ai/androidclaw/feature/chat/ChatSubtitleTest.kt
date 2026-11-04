package ai.androidclaw.feature.chat

import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSubtitleTest {
    @Test
    fun `main session subtitle shows the model instead of repeating the title`() {
        assertEquals("gpt-6-luna", chatSubtitle(title = "主会话", mainLabel = "主会话", modelLabel = "gpt-6-luna"))
        assertEquals("gpt-6-luna", chatSubtitle(title = "Main session", mainLabel = "Main session", modelLabel = "gpt-6-luna"))
        assertNull(chatSubtitle(title = "Main session", mainLabel = "Main session", modelLabel = null))
    }

    @Test
    fun `renamed main session keeps the main label and other sessions show only the model`() {
        assertEquals("Main session · gpt-6-luna", chatSubtitle(title = "Daily", mainLabel = "Main session", modelLabel = "gpt-6-luna"))
        assertEquals("gpt-6-luna", chatSubtitle(title = "会话 14", mainLabel = null, modelLabel = "gpt-6-luna"))
        assertNull(chatSubtitle(title = "会话 14", mainLabel = null, modelLabel = " "))
    }

    @Test
    fun `model label is the model id, else the provider name, and null for the demo`() {
        assertNull(ProviderSettingsSnapshot(providerType = ProviderType.Fake).chatModelLabel())
        assertEquals("gpt-6-luna", ProviderSettingsSnapshot(providerType = ProviderType.OpenAi).chatModelLabel())
        val blankModel =
            ProviderSettingsSnapshot(providerType = ProviderType.OpenAiCompatible)
                .withEndpointSettings(
                    ProviderType.OpenAiCompatible,
                    ProviderEndpointSettings(baseUrl = "https://example.com/v1", modelId = " ", timeoutSeconds = 60),
                )
        assertEquals("OpenAI-compatible", blankModel.chatModelLabel())
    }
}
