package ai.androidclaw.feature.chat

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renameDraft by rememberSaveable(state.currentSessionId) { mutableStateOf(state.sessionTitle) }

    LaunchedEffect(state.sessionTitle, state.currentSessionId) {
        renameDraft = state.sessionTitle
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                Card(modifier = Modifier.fillMaxWidth()) {
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
