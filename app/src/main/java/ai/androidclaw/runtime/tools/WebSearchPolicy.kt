package ai.androidclaw.runtime.tools

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.WebSearchKeyStore
import ai.androidclaw.runtime.providers.isOfficialOpenAiHost
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val WEB_SEARCH_TOOL_NAME = "web_search"

/** Which search path a turn uses. Exactly one rule decides it: [resolveWebSearchMode]. */
enum class WebSearchMode {
    /** Typed `web_search` tool backed by the Brave Search API (any provider, scheduled runs too). */
    Typed,

    /** The provider's own search tool (OpenAI Responses `web_search`, Claude `web_search`). */
    Hosted,

    Off,
}

enum class WebSearchOffReason {
    DisabledInSettings,
    ProviderHasNoHostedSearch,
}

data class WebSearchResolution(
    val mode: WebSearchMode,
    val offReason: WebSearchOffReason? = null,
) {
    companion object {
        val Typed = WebSearchResolution(WebSearchMode.Typed)
        val Hosted = WebSearchResolution(WebSearchMode.Hosted)

        fun off(reason: WebSearchOffReason) = WebSearchResolution(WebSearchMode.Off, reason)
    }
}

/**
 * The search rule (PLANv13 M2):
 * 1. Search turned off in Settings → off.
 * 2. Brave key saved → typed `web_search` tool.
 * 3. Provider with hosted search → hosted. OpenAI (and OpenAI-compatible routed to
 *    api.openai.com, which uses /v1/responses) and Claude. Codex stays off: its endpoint was not
 *    verified with the hosted tool.
 * 4. Otherwise off.
 */
fun resolveWebSearchMode(
    enabled: Boolean,
    hasBraveKey: Boolean,
    providerType: ProviderType,
    baseUrl: String,
): WebSearchResolution =
    when {
        !enabled -> WebSearchResolution.off(WebSearchOffReason.DisabledInSettings)
        hasBraveKey -> WebSearchResolution.Typed
        providerHasHostedSearch(providerType, baseUrl) -> WebSearchResolution.Hosted
        else -> WebSearchResolution.off(WebSearchOffReason.ProviderHasNoHostedSearch)
    }

internal fun providerHasHostedSearch(
    providerType: ProviderType,
    baseUrl: String,
): Boolean =
    when (providerType) {
        ProviderType.Anthropic -> true
        ProviderType.OpenAi, ProviderType.OpenAiCompatible ->
            baseUrl.trim().toHttpUrlOrNull()?.isOfficialOpenAiHost() == true
        else -> false
    }

/** Reads the inputs of [resolveWebSearchMode] from storage. */
class WebSearchPolicy(
    private val settingsDataStore: SettingsDataStore,
    private val keyStore: WebSearchKeyStore,
) {
    suspend fun resolve(providerSettings: ProviderSettingsSnapshot? = null): WebSearchResolution {
        val settings = providerSettings ?: settingsDataStore.settings.first()
        return resolveWebSearchMode(
            enabled = settingsDataStore.webSearchEnabled.first(),
            hasBraveKey = !keyStore.readBraveApiKey().isNullOrBlank(),
            providerType = settings.providerType,
            baseUrl = settings.endpointSettings(settings.providerType).baseUrl,
        )
    }
}
