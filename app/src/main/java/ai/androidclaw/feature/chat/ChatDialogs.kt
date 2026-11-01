package ai.androidclaw.feature.chat

import ai.androidclaw.R
import ai.androidclaw.ui.components.ClawIconButton
import ai.androidclaw.ui.components.ClawListItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
internal fun RenameSessionDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // The current name starts selected and focused, so typing replaces it.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialTitle, selection = TextRange(0, initialTitle.length)))
    }
    val title = field.text
    val focusRequester = remember { FocusRequester() }
    var truncated by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename_title)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { value ->
                    val bounded = boundChatRenameInput(value.text)
                    field = if (bounded.value == value.text) value else TextFieldValue(bounded.value, TextRange(bounded.value.length))
                    truncated = bounded.wasTruncated
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("chatRenameField"),
                label = { Text(stringResource(R.string.chat_rename_label)) },
                singleLine = true,
                supportingText =
                    if (truncated) {
                        { Text(stringResource(R.string.chat_rename_truncated)) }
                    } else {
                        null
                    },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (title.isNotBlank()) onConfirm(title) }),
            )
            // Inside the dialog content so the field is attached before focus is requested.
            LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title) },
                enabled = title.isNotBlank(),
                modifier = Modifier.testTag("chatRenameConfirm"),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun UsageDialog(
    usage: ChatUsageUi?,
    sessionSummary: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_usage_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (usage == null) {
                    Text(stringResource(R.string.chat_usage_empty))
                } else {
                    Text(pluralStringResource(R.plurals.chat_usage_replies, usage.trackedReplies, usage.trackedReplies))
                    if (usage.inputTokens > 0) {
                        Text(stringResource(R.string.chat_usage_input_tokens, usage.inputTokens))
                    }
                    if (usage.outputTokens > 0) {
                        Text(stringResource(R.string.chat_usage_output_tokens, usage.outputTokens))
                    }
                    if (usage.totalTokens > 0) {
                        Text(stringResource(R.string.chat_usage_total_tokens, usage.totalTokens))
                    }
                }
                sessionSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text(
                        text = stringResource(R.string.chat_usage_summary_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(text = summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchSheet(
    state: ChatUiState,
    onQueryChanged: (String) -> Unit,
    onRunSearch: () -> Unit,
    onClear: () -> Unit,
    onOpenResult: (ChatSearchResultUi) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQueryChanged,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("chatSearchField"),
                label = { Text(stringResource(R.string.chat_search_label)) },
                singleLine = true,
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        ClawIconButton(
                            iconRes = R.drawable.ic_close,
                            contentDescription = stringResource(R.string.chat_search_clear),
                            onClick = onClear,
                        )
                    } else {
                        ClawIconButton(
                            iconRes = R.drawable.ic_search,
                            contentDescription = stringResource(R.string.chat_action_search),
                            onClick = onRunSearch,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onRunSearch() }),
            )
            when {
                state.searchResults.isNotEmpty() ->
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(state.searchResults, key = { "${it.sessionId}:${it.messageId}:${it.matchType}" }) { result ->
                            ClawListItem(
                                title = result.sessionTitle,
                                supporting =
                                    when (result.matchType) {
                                        ChatSearchMatchType.Session -> stringResource(R.string.chat_search_session_match)
                                        ChatSearchMatchType.Message -> result.preview
                                    },
                                leadingIcon =
                                    when (result.matchType) {
                                        ChatSearchMatchType.Session -> R.drawable.ic_nav_chat
                                        ChatSearchMatchType.Message -> R.drawable.ic_search
                                    },
                                onClick = { onOpenResult(result) },
                            )
                        }
                    }

                state.searchPerformed ->
                    Text(
                        text = stringResource(R.string.chat_search_no_results, state.searchQuery.trim()),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                else ->
                    Text(
                        text = stringResource(R.string.chat_search_hint),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareSheet(
    onShareText: () -> Unit,
    onShareFile: () -> Unit,
    onExport: (ChatExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_share_title),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            ClawListItem(
                title = stringResource(R.string.chat_share_text),
                supporting = stringResource(R.string.chat_share_text_hint),
                leadingIcon = R.drawable.ic_share,
                onClick = onShareText,
            )
            ClawListItem(
                title = stringResource(R.string.chat_share_markdown_file),
                leadingIcon = R.drawable.ic_share,
                onClick = onShareFile,
            )
            ClawListItem(
                title = stringResource(R.string.chat_export_markdown),
                leadingIcon = R.drawable.ic_upload,
                onClick = { onExport(ChatExportFormat.Markdown) },
            )
            ClawListItem(
                title = stringResource(R.string.chat_export_text),
                leadingIcon = R.drawable.ic_upload,
                onClick = { onExport(ChatExportFormat.Text) },
            )
            ClawListItem(
                title = stringResource(R.string.chat_export_json),
                leadingIcon = R.drawable.ic_upload,
                onClick = { onExport(ChatExportFormat.Json) },
            )
        }
    }
}
