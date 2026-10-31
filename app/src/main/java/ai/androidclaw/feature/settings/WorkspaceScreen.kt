package ai.androidclaw.feature.settings

import ai.androidclaw.R
import ai.androidclaw.ui.components.ClawConfirmDialog
import ai.androidclaw.ui.components.ClawEmptyState
import ai.androidclaw.ui.components.ClawIcon
import ai.androidclaw.ui.components.ClawIconButton
import ai.androidclaw.ui.components.ClawListItem
import ai.androidclaw.ui.components.ClawNotice
import ai.androidclaw.ui.components.ClawSectionHeader
import ai.androidclaw.ui.components.ClawTopBar
import ai.androidclaw.ui.components.NoticeTone
import ai.androidclaw.ui.text.asString
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Settings > Assistant workspace: persona files first, then notes the assistant wrote. */
@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    onOpenFile: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        modifier = Modifier.testTag("workspaceScreen"),
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            ClawTopBar(
                title = stringResource(R.string.workspace_title),
                titleTestTag = "workspaceHeading",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_intro),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val overBudget = state.personaBytes > state.promptBudgetBytes
            ClawNotice(
                text =
                    stringResource(
                        R.string.workspace_budget,
                        Formatter.formatShortFileSize(context, state.personaBytes),
                        Formatter.formatShortFileSize(context, state.promptBudgetBytes.toLong()),
                    ) + if (overBudget) "\n" + stringResource(R.string.workspace_budget_over) else "",
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("workspaceBudget"),
                tone = if (overBudget) NoticeTone.Warning else NoticeTone.Info,
            )
            state.errorMessage?.let { message ->
                ClawNotice(
                    text = message.asString(),
                    modifier = Modifier.padding(16.dp),
                    tone = NoticeTone.Error,
                    onDismiss = viewModel::clearMessages,
                )
            }
            when {
                state.loading ->
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                state.files.isEmpty() ->
                    ClawEmptyState(
                        iconRes = R.drawable.ic_edit,
                        title = stringResource(R.string.workspace_empty),
                        body = stringResource(R.string.workspace_intro),
                    )

                else -> {
                    val (persona, notes) = state.files.partition(WorkspaceFileUi::isPersonaFile)
                    ClawSectionHeader(stringResource(R.string.workspace_section_persona))
                    persona.forEach { file -> WorkspaceFileRow(file = file, onOpenFile = onOpenFile) }
                    if (notes.isNotEmpty()) {
                        ClawSectionHeader(stringResource(R.string.workspace_section_notes))
                        notes.forEach { file -> WorkspaceFileRow(file = file, onOpenFile = onOpenFile) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFileRow(
    file: WorkspaceFileUi,
    onOpenFile: (String) -> Unit,
) {
    val context = LocalContext.current
    ClawListItem(
        title = file.path,
        supporting =
            stringResource(
                R.string.workspace_file_size,
                stringResource(workspaceFileDescription(file.path)),
                Formatter.formatShortFileSize(context, file.sizeBytes),
            ),
        leadingIcon = R.drawable.ic_edit,
        onClick = { onOpenFile(file.path) },
        modifier = Modifier.testTag("workspaceFile-${file.path}"),
        trailing = { ClawIcon(iconRes = R.drawable.ic_chevron_right, contentDescription = null) },
    )
}

private fun workspaceFileDescription(path: String): Int =
    when (path) {
        "AGENTS.md" -> R.string.workspace_file_agents
        "SOUL.md" -> R.string.workspace_file_soul
        "USER.md" -> R.string.workspace_file_user
        "MEMORY.md" -> R.string.workspace_file_memory
        else -> R.string.workspace_file_note
    }

/** Monospace editor for one workspace file with save, reset-to-default and a size indicator. */
@Composable
fun WorkspaceEditorScreen(
    viewModel: WorkspaceViewModel,
    path: String,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(path) { viewModel.open(path) }

    val text = viewModel.editorText
    val dirty = !state.editorLoading && state.editorPath == path && text != state.editorOriginal
    val textBytes = remember(text) { utf8ByteCount(text) }
    val close = {
        viewModel.closeEditor()
        onClose()
    }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        modifier = Modifier.testTag("workspaceEditorScreen"),
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            ClawTopBar(
                title = path,
                titleTestTag = "workspaceEditorHeading",
                subtitle = if (dirty) stringResource(R.string.workspace_unsaved) else null,
                onBack = { if (dirty) confirmDiscard = true else close() },
                actions = {
                    if (state.editorIsPersona) {
                        ClawIconButton(
                            iconRes = R.drawable.ic_refresh,
                            contentDescription = stringResource(R.string.workspace_action_reset),
                            onClick = { confirmReset = true },
                            enabled = !state.saving && !state.editorLoading,
                            modifier = Modifier.testTag("workspaceResetButton"),
                        )
                    }
                    ClawIconButton(
                        iconRes = R.drawable.ic_check,
                        contentDescription = stringResource(R.string.action_save),
                        onClick = viewModel::save,
                        enabled = dirty && !state.saving && textBytes <= state.maxFileBytes,
                        modifier = Modifier.testTag("workspaceSaveButton"),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val overFileLimit = textBytes > state.maxFileBytes
            val overPromptBudget = state.editorIsPersona && textBytes > state.promptBudgetBytes
            Text(
                text =
                    stringResource(
                        R.string.workspace_editor_size,
                        Formatter.formatShortFileSize(context, textBytes.toLong()),
                        Formatter.formatShortFileSize(context, state.maxFileBytes.toLong()),
                    ) + " · " +
                        stringResource(
                            if (state.editorIsPersona) R.string.workspace_editor_in_prompt else R.string.workspace_editor_not_in_prompt,
                        ),
                modifier = Modifier.testTag("workspaceEditorSize"),
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (overFileLimit || overPromptBudget) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            if (overPromptBudget) {
                Text(
                    text = stringResource(R.string.workspace_budget_over),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            (state.errorMessage ?: state.message)?.let { message ->
                ClawNotice(
                    text = message.asString(),
                    modifier = Modifier.testTag("workspaceEditorNotice"),
                    tone = if (state.errorMessage != null) NoticeTone.Error else NoticeTone.Success,
                    onDismiss = viewModel::clearMessages,
                )
            }
            if (state.editorLoading || state.editorPath != path) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val editorLabel = stringResource(R.string.workspace_editor_label, path)
                OutlinedTextField(
                    value = text,
                    onValueChange = viewModel::onEditorTextChanged,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .semantics { contentDescription = editorLabel }
                            .testTag("workspaceEditorField"),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    enabled = !state.saving,
                )
            }
        }
    }

    if (confirmReset) {
        ClawConfirmDialog(
            title = stringResource(R.string.workspace_reset_title, path),
            text = stringResource(R.string.workspace_reset_body),
            confirmLabel = stringResource(R.string.workspace_reset_confirm),
            destructive = true,
            onConfirm = {
                confirmReset = false
                viewModel.resetToDefault()
            },
            onDismiss = { confirmReset = false },
        )
    }
    if (confirmDiscard) {
        ClawConfirmDialog(
            title = stringResource(R.string.workspace_discard_title),
            text = stringResource(R.string.workspace_discard_body, path),
            confirmLabel = stringResource(R.string.workspace_discard),
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                close()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}
