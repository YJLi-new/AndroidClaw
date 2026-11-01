package ai.androidclaw.feature.chat

import ai.androidclaw.R
import ai.androidclaw.ui.components.ClawIcon
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.format.DateTimeFormatter

private const val TOOL_DETAIL_MAX_CHARS = 4_000

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatMessageRow(
    message: ChatMessageUi,
    highlighted: Boolean,
    timeFormatter: DateTimeFormatter,
    onLongPress: (ChatMessageUi) -> Unit,
    onOpenAttachment: (ChatAttachmentUi) -> Unit = {},
) {
    val copyLabel = stringResource(R.string.chat_action_copy_message)
    val pressModifier =
        Modifier.combinedClickable(
            onClick = {},
            onLongClick = { onLongPress(message) },
            onLongClickLabel = copyLabel,
        )
    val highlightColor = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent
    when (message.role) {
        ChatRole.User ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(highlightColor)
                        .padding(start = 56.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
                        .testTag("chatMessage-user"),
                horizontalAlignment = Alignment.End,
            ) {
                ChatAttachmentGrid(
                    attachments = message.attachments,
                    onOpen = onOpenAttachment,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (message.text.isNotBlank() || message.attachments.isEmpty()) {
                    Surface(
                        modifier = pressModifier,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        ChatRichText(
                            text = message.text,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onPrimaryContainer),
                        )
                    }
                }
                MessageTime(message.createdAt, timeFormatter)
            }

        ChatRole.Assistant ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(highlightColor)
                        .then(pressModifier)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("chatMessage-assistant"),
            ) {
                ChatRichText(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                )
                MessageTime(message.createdAt, timeFormatter)
            }

        ChatRole.System, ChatRole.ToolCall, ChatRole.ToolResult ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(highlightColor)
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                        .testTag("chatMessage-system"),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = pressModifier,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        text = localizedRuntimeNotice(message.text),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
    }
}

/** System rows persisted by the runtime are English; show the known ones in the app language. */
@Composable
private fun localizedRuntimeNotice(text: String): String {
    val trimmed = text.trim()
    return when {
        trimmed == "AndroidClaw is ready." -> stringResource(R.string.chat_notice_ready)
        trimmed == "Turn cancelled." -> stringResource(R.string.chat_notice_turn_cancelled)
        trimmed.startsWith("Turn failed:") ->
            stringResource(R.string.chat_notice_turn_failed, trimmed.removePrefix("Turn failed:").trim())
        else -> text
    }
}

@Composable
private fun MessageTime(
    createdAt: Instant,
    formatter: DateTimeFormatter,
) {
    Text(
        text = formatter.format(createdAt),
        modifier = Modifier.padding(top = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One collapsible card for a tool request and its result. */
@Composable
internal fun ChatToolCard(
    item: ChatItemUi.Tool,
    running: Boolean,
) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val view = remember(item.result) { item.result?.let(::parseToolResultView) }
    val arguments = remember(item.arguments) { formatToolArguments(item.arguments) }
    val toolName = item.toolName.ifBlank { stringResource(R.string.chat_tool_unknown) }
    val errorCode = view?.errorCode
    val failed = errorCode != null
    val title = view?.headline?.takeIf(String::isNotBlank) ?: toolName
    val status =
        when {
            errorCode != null -> stringResource(R.string.chat_tool_status_failed, errorCode)
            view != null -> stringResource(R.string.chat_tool_status_done)
            running -> stringResource(R.string.chat_tool_status_running)
            else -> stringResource(R.string.chat_tool_status_no_result)
        }
    val subtitle = if (title == toolName) status else "$toolName · $status"
    val resultBody =
        view
            ?.let { listOfNotNull(it.summary.takeIf { summary -> summary != it.headline }, it.payload) }
            ?.filter(String::isNotBlank)
            ?.joinToString("\n\n")
            .orEmpty()
    val expandLabel =
        stringResource(if (expanded) R.string.chat_tool_collapse else R.string.chat_tool_expand)
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("chatToolCard"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = expandLabel,
                            onClick = { expanded = !expanded },
                        ).semantics { stateDescription = status }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (running && item.result == null) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    ClawIcon(
                        iconRes = if (failed) R.drawable.ic_error else R.drawable.ic_build,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = if (expanded) 4 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ClawIcon(
                    iconRes = if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            toolErrorHint(errorCode)
                ?.let { stringResource(it) }
                ?.takeUnless { hint ->
                    // The runtime summary is English; skip the hint when it already says the same thing.
                    view?.summary?.startsWith(hint.trimEnd('.')) == true
                }?.let { hint ->
                    Text(
                        text = hint,
                        modifier =
                            Modifier
                                .padding(start = 40.dp, end = 12.dp, bottom = 8.dp)
                                .testTag("chatToolHint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (arguments.isNotBlank() && arguments != "{}") {
                        ToolDetail(label = stringResource(R.string.chat_tool_arguments), body = arguments)
                    }
                    if (resultBody.isNotBlank()) {
                        ToolDetail(label = stringResource(R.string.chat_tool_result), body = resultBody)
                    }
                }
            }
        }
    }
}

/** A short, localized next step for tool failures the user can fix. */
internal fun toolErrorHint(errorCode: String?): Int? =
    when (errorCode) {
        "MEMORY_DISABLED" -> R.string.chat_tool_hint_memory_disabled
        "FOREGROUND_REQUIRED" -> R.string.chat_tool_hint_foreground_required
        "PERMISSION_REQUIRED" -> R.string.chat_tool_hint_permission_required
        else -> null
    }

@Composable
private fun ToolDetail(
    label: String,
    body: String,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = body.take(TOOL_DETAIL_MAX_CHARS),
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** In-flight assistant turn: streamed text (if any) plus the current stage. */
@Composable
internal fun ChatStreamingRow(
    text: String,
    stage: String?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("chatStreaming"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (text.isNotBlank()) {
            ChatRichText(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            )
        }
        Row(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text = stage ?: stringResource(R.string.chat_stage_waiting),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
