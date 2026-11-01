package ai.androidclaw.feature.chat

import ai.androidclaw.R
import ai.androidclaw.runtime.providers.WebSource
import ai.androidclaw.runtime.providers.parseProviderMessageMeta
import ai.androidclaw.ui.components.ClawIcon
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a reply searched for and the web sources behind it (from the message's providerMeta). */
data class ChatWebSearchUi(
    val queries: List<String>,
    val sources: List<WebSource>,
)

/** Parses providerMeta only when it mentions search, so plain replies cost no JSON decoding. */
internal fun chatWebSearchFromMeta(providerMeta: String?): ChatWebSearchUi? {
    if (providerMeta == null || (!providerMeta.contains("\"sources\"") && !providerMeta.contains("\"webSearchQueries\""))) {
        return null
    }
    val meta = parseProviderMessageMeta(providerMeta) ?: return null
    if (meta.sources.isEmpty() && meta.webSearchQueries.isEmpty()) {
        return null
    }
    return ChatWebSearchUi(queries = meta.webSearchQueries, sources = meta.sources)
}

internal fun WebSource.displayLabel(): String =
    title?.takeIf { it.isNotBlank() }
        ?: url.toHttpUrlOrNull()?.host?.removePrefix("www.")
        ?: url

/** "Searched the web: <query>" cards and a Sources row under an assistant reply. */
@Composable
internal fun ChatSources(
    webSearch: ChatWebSearchUi,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier.padding(top = 6.dp).testTag("chatWebSearch"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        webSearch.queries.forEach { query ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ClawIcon(iconRes = R.drawable.ic_search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(R.string.chat_web_searched, query),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("chatWebSearchQuery"),
                    )
                }
            }
        }
        if (webSearch.sources.isNotEmpty()) {
            Text(
                text = stringResource(R.string.chat_web_sources),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                webSearch.sources.forEachIndexed { index, source ->
                    AssistChip(
                        onClick = { runCatching { uriHandler.openUri(source.url) } },
                        label = {
                            Text(
                                text = "${index + 1}. ${source.displayLabel()}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier =
                            Modifier
                                .widthIn(max = 240.dp)
                                .testTag("chatSource-$index"),
                    )
                }
            }
        }
    }
}
