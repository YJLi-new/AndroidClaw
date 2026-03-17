package ai.androidclaw.feature.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.flow.collect

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var renameDraft by rememberSaveable(state.currentSessionId) { mutableStateOf(state.sessionTitle) }
    var pendingExport by remember { mutableStateOf<ChatExportPayload?>(null) }
    val messageListState = rememberLazyListState()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val payload = pendingExport
        pendingExport = null
        if (payload == null) return@rememberLauncherForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            viewModel.onExportCancelled()
            return@rememberLauncherForActivityResult
        }
        val uri = result.data?.data
        if (uri == null) {
            viewModel.onExternalActionFailed("Failed to export ${payload.fileName}: no file destination selected.")
            return@rememberLauncherForActivityResult
        }
        runCatching {
            writeExportPayload(context, uri, payload)
        }.onSuccess {
            viewModel.onExternalActionCompleted("Saved ${payload.fileName}.")
        }.onFailure { error ->
            viewModel.onExternalActionFailed(
                "Failed to export ${payload.fileName}: ${error.message ?: "unknown error"}.",
            )
        }
    }

    LaunchedEffect(state.sessionTitle, state.currentSessionId) {
        renameDraft = state.sessionTitle
    }

    LaunchedEffect(state.highlightedMessageId, state.messages) {
        val highlightedMessageId = state.highlightedMessageId ?: return@LaunchedEffect
        val targetIndex = state.messages.indexOfFirst { it.id == highlightedMessageId }
        if (targetIndex >= 0) {
            messageListState.animateScrollToItem(targetIndex)
        }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.actions.collect { action ->
            when (action) {
                is ChatExternalAction.ExportDocument -> {
                    pendingExport = action.payload
                    exportLauncher.launch(createExportDocumentIntent(action.payload))
                }

                is ChatExternalAction.ShareText -> {
                    launchShareText(context, action)
                }

                is ChatExternalAction.ShareFile -> {
                    runCatching {
                        val uri = writeShareFile(context, action.payload)
                        launchShareFile(context, action.payload, uri)
                    }.onFailure { error ->
                        viewModel.onExternalActionFailed(
                            "Failed to share ${action.payload.fileName}: ${error.message ?: "unknown error"}.",
                        )
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .testTag("chatScreen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = state.sessionTitle.ifBlank { "Loading session..." },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = renameDraft,
                onValueChange = { renameDraft = it },
                modifier = Modifier.weight(1f),
                label = { Text("Session title") },
                singleLine = true,
                enabled = !state.isRunning,
            )
            Button(
                onClick = { viewModel.renameCurrentSession(renameDraft) },
                enabled = state.currentSessionId.isNotBlank() && !state.isRunning,
            ) {
                Text("Rename")
            }
            Button(
                onClick = viewModel::archiveCurrentSession,
                enabled = state.canArchiveCurrentSession && !state.isRunning,
            ) {
                Text("Archive")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier.weight(1f),
                label = { Text("Search sessions and messages") },
                singleLine = true,
                enabled = !state.isRunning,
            )
            Button(
                onClick = viewModel::runSearch,
                enabled = !state.isRunning,
            ) {
                Text("Search")
            }
            Button(
                onClick = viewModel::clearSearch,
                enabled = !state.isRunning && (state.searchQuery.isNotBlank() || state.searchResults.isNotEmpty()),
            ) {
                Text("Clear")
            }
        }
        if (state.searchResults.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Search results",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    state.searchResults.forEach { result ->
                        Button(
                            onClick = { viewModel.openSearchResult(result) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isRunning,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text("${result.matchType}: ${result.sessionTitle}")
                                Text(result.preview, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        state.providerNotice?.let { providerNotice ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Provider",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = providerNotice,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Export and share",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        AssistChip(
                            onClick = { viewModel.exportCurrentSession(ChatExportFormat.Text) },
                            label = { Text("Export TXT") },
                            enabled = state.currentSessionId.isNotBlank() && !state.isRunning,
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { viewModel.exportCurrentSession(ChatExportFormat.Markdown) },
                            label = { Text("Export MD") },
                            enabled = state.currentSessionId.isNotBlank() && !state.isRunning,
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { viewModel.exportCurrentSession(ChatExportFormat.Json) },
                            label = { Text("Export JSON") },
                            enabled = state.currentSessionId.isNotBlank() && !state.isRunning,
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        AssistChip(
                            onClick = viewModel::shareCurrentSessionAsText,
                            label = { Text("Share text") },
                            enabled = state.currentSessionId.isNotBlank() && !state.isRunning,
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { viewModel.shareCurrentSessionAsFile(ChatExportFormat.Markdown) },
                            label = { Text("Share file") },
                            enabled = state.currentSessionId.isNotBlank() && !state.isRunning,
                        )
                    }
                }
            }
        }
        state.sessionSummary?.takeIf { it.isNotBlank() }?.let { sessionSummary ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Session summary",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = sessionSummary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        state.noticeMessage?.let { noticeMessage ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Status",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = noticeMessage,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        state.errorMessage?.let { errorMessage ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Turn failed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.canRetryLastFailedTurn) {
                        Button(onClick = viewModel::retryLastFailedTurn) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.sessions, key = { it.id }) { session ->
                FilterChip(
                    selected = session.isSelected,
                    onClick = { viewModel.switchSession(session.id) },
                    label = { Text(session.title) },
                    enabled = !state.isRunning,
                )
            }
            item {
                AssistChip(
                    onClick = { viewModel.createNewSession("Session ${state.sessions.size + 1}") },
                    label = { Text("New session") },
                    enabled = !state.isRunning,
                )
            }
        }
        if (state.slashCommands.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.slashCommands.take(3).forEach { command ->
                    AssistChip(
                        onClick = { viewModel.onDraftChanged("$command ") },
                        label = { Text(command) },
                        enabled = !state.isRunning,
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = messageListState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (message.id == state.highlightedMessageId) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = message.role.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            if (state.streamingAssistantText.isNotBlank() || (state.isRunning && state.activeTurnStage != null)) {
                item(key = "streaming-assistant") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "ASSISTANT",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            state.activeTurnStage?.let { stage ->
                                Text(
                                    text = stage,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            Text(
                                text = state.streamingAssistantText.ifBlank { "Working..." },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
        if (state.isRunning) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = state.activeTurnStage ?: if (state.isCancelling) "Cancelling..." else "Waiting for response",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = viewModel::cancelActiveTurn,
                        enabled = !state.isCancelling,
                    ) {
                        Text(if (state.isCancelling) "Cancelling" else "Cancel")
                    }
                }
            }
        }
        OutlinedTextField(
            value = state.draft,
            onValueChange = viewModel::onDraftChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message or slash command") },
            minLines = 2,
            enabled = !state.isRunning,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.canRetryLastFailedTurn && !state.isRunning) {
                Button(onClick = viewModel::retryLastFailedTurn) {
                    Text("Retry")
                }
            }
            Button(
                onClick = viewModel::sendCurrentDraft,
                enabled = !state.isRunning && state.draft.isNotBlank(),
            ) {
                Text("Send")
            }
        }
    }
}

private fun createExportDocumentIntent(payload: ChatExportPayload): Intent {
    return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = payload.mimeType
        putExtra(Intent.EXTRA_TITLE, payload.fileName)
    }
}

private fun writeExportPayload(
    context: Context,
    uri: Uri,
    payload: ChatExportPayload,
) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
        requireNotNull(writer) { "Unable to open destination for ${payload.fileName}" }
        writer.write(payload.content)
    }
}

private fun launchShareText(
    context: Context,
    action: ChatExternalAction.ShareText,
) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, action.subject)
        putExtra(Intent.EXTRA_TEXT, action.text)
    }
    context.startActivity(Intent.createChooser(intent, "Share session"))
}

private fun writeShareFile(
    context: Context,
    payload: ChatExportPayload,
): Uri {
    val exportDirectory = File(context.cacheDir, "chat-exports").apply { mkdirs() }
    val exportFile = File(exportDirectory, payload.fileName)
    exportFile.writeText(payload.content)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.chat-export-provider",
        exportFile,
    )
}

private fun launchShareFile(
    context: Context,
    payload: ChatExportPayload,
    uri: Uri,
) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = payload.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, payload.fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share session file"))
}
