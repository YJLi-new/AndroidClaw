package ai.androidclaw.feature.skills

import ai.androidclaw.R
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillResolutionState
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.skills.SkillSourceType
import ai.androidclaw.ui.components.ClawActionPill
import ai.androidclaw.ui.components.ClawCard
import ai.androidclaw.ui.components.ClawFactRow
import ai.androidclaw.ui.components.ClawGreen
import ai.androidclaw.ui.components.ClawGreenMuted
import ai.androidclaw.ui.components.ClawInk
import ai.androidclaw.ui.components.ClawInkMuted
import ai.androidclaw.ui.components.ClawPage
import ai.androidclaw.ui.components.ClawScreenHeader
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SkillsScreen(viewModel: SkillsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                viewModel.importLocalSkills(uri)
            }
        }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    ClawPage(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ClawScreenHeader(
                iconRes = R.drawable.ic_nav_skills,
                title = "Skills",
                subtitle = "Manage bundled, local, and workspace SKILL.md capabilities.",
                titleTestTag = "skillsHeading",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ClawActionPill(
                    text = "Refresh",
                    onClick = viewModel::refresh,
                    enabled = !state.isImporting,
                    selected = true,
                    modifier = Modifier.weight(1f),
                )
                ClawActionPill(
                    text = if (state.isImporting) "Importing..." else "Import zip",
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                    enabled = !state.isImporting,
                    modifier = Modifier.weight(1f),
                )
            }
            state.statusMessage?.let { message ->
                ClawCard(containerColor = ClawGreenMuted) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = viewModel::clearStatusMessage) {
                            Text("Dismiss")
                        }
                    }
                }
            }
            if (state.loading && state.skills.isEmpty()) {
                Text("Loading bundled and local SKILL.md files...")
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.skills, key = { it.id }) { skill ->
                    SkillCard(
                        skill = skill,
                        onToggle = { enabled ->
                            viewModel.toggleSkill(
                                skillId = skill.id,
                                enabled = enabled,
                            )
                        },
                        onConfigure =
                            if (skill.hasConfigSurface()) {
                                { viewModel.openConfiguration(skill) }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }

    state.configurationDialog?.let { dialog ->
        SkillConfigurationDialog(
            state = dialog,
            onDismiss = viewModel::dismissConfiguration,
            onDismissMessage = viewModel::clearConfigurationMessage,
            onSecretChanged = viewModel::updateSecretDraft,
            onSecretClear = viewModel::requestSecretClear,
            onConfigChanged = viewModel::updateConfigDraft,
            onConfigClear = viewModel::requestConfigClear,
            onSave = viewModel::saveConfiguration,
        )
    }
    state.enableConfirmation?.let { confirmation ->
        SkillEnableConfirmationDialog(
            state = confirmation,
            onConfirm = viewModel::confirmEnableSkill,
            onDismiss = viewModel::dismissEnableConfirmation,
        )
    }
}

@Composable
private fun SkillCard(
    skill: SkillSnapshot,
    onToggle: (Boolean) -> Unit,
    onConfigure: (() -> Unit)?,
) {
    ClawCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(ClawGreenMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = skill.displayName.take(1).uppercase(),
                        color = ClawGreen,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(skill.displayName, style = MaterialTheme.typography.titleMedium, color = ClawInk)
                    Text(
                        text = "Source: ${skill.sourceLabel()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ClawGreen,
                    )
                    if (skill.resolutionState == SkillResolutionState.Shadowed) {
                        Text(
                            text = "Shadowed by ${skill.shadowedBy.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Switch(
                    modifier =
                        Modifier.semantics {
                            stateDescription = if (skill.enabled) "Enabled" else "Disabled"
                        },
                    checked = skill.enabled,
                    onCheckedChange = onToggle,
                )
            }
            Text(
                skill.frontmatter?.description ?: skill.parseError.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = ClawInkMuted,
            )
            EligibilitySummary(skill = skill)
            skill.statusSummary(label = "Secrets", statuses = skill.secretStatuses)?.let { summary ->
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
            skill.statusSummary(label = "Config", statuses = skill.configStatuses)?.let { summary ->
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
            skill.frontmatter?.commandTool?.let { commandTool ->
                ClawFactRow(label = "Dispatch tool", value = commandTool)
            }
            if (skill.sourceType != SkillSourceType.Bundled && !skill.enabled) {
                Text(
                    text = "Enabling this imported skill requires confirmation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClawInkMuted,
                )
            }
            if (skill.skillKey != skill.displayName) {
                Text(
                    text = "Skill key: ${skill.skillKey}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (onConfigure != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onConfigure) {
                        Text("Configure")
                    }
                }
            }
        }
    }
}

@Composable
private fun EligibilitySummary(skill: SkillSnapshot) {
    val eligibilityText =
        when (skill.eligibility.status) {
            SkillEligibilityStatus.Eligible -> "Eligibility: ready"
            else -> "Eligibility: ${skill.eligibility.status}"
        }
    Text(
        text = eligibilityText,
        style = MaterialTheme.typography.labelMedium,
        color = ClawInk,
    )
    if (skill.eligibility.reasons.isNotEmpty()) {
        Text(
            text = skill.eligibility.reasons.joinToString(separator = "\n"),
            style = MaterialTheme.typography.bodySmall,
            color = ClawInkMuted,
        )
    }
}

@Composable
private fun SkillEnableConfirmationDialog(
    state: SkillEnableConfirmationState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable imported skill?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Skill: ${state.displayName}", style = MaterialTheme.typography.titleSmall)
                Text("Source: ${state.sourceLabel}", style = MaterialTheme.typography.bodyMedium)
                Text(state.dispatchSummary, style = MaterialTheme.typography.bodyMedium)
                Text(state.scopeSummary, style = MaterialTheme.typography.bodySmall, color = ClawInkMuted)
                Text(
                    text = "Only enable imported skills you trust. They can influence model context or slash-command tool dispatch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClawInkMuted,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SkillConfigurationDialog(
    state: SkillConfigurationDialogState,
    onDismiss: () -> Unit,
    onDismissMessage: () -> Unit,
    onSecretChanged: (String, String) -> Unit,
    onSecretClear: (String) -> Unit,
    onConfigChanged: (String, String) -> Unit,
    onConfigClear: (String) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!state.saving) {
                onDismiss()
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Configure ${state.displayName}")
                Text(
                    text = state.skillKey,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.loading) {
                    Text("Loading skill configuration…")
                }
                state.message?.let { message ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onDismissMessage) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
                if (!state.loading && !state.hasEditableFields) {
                    Text("This skill does not declare editable secrets or config values.")
                }
                state.secretFields.forEach { field ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(field.envName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text =
                                when {
                                    field.clearRequested -> "Stored value will be cleared on save."
                                    field.configured -> "A value is already saved. Enter a replacement to overwrite it."
                                    else -> "No value saved yet."
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = field.draftValue,
                            onValueChange = { value -> onSecretChanged(field.envName, value) },
                            label = { Text("Secret value") },
                            enabled = !state.loading && !state.saving,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = { onSecretClear(field.envName) },
                                enabled = !state.loading && !state.saving && (field.configured || field.draftValue.isNotEmpty() || field.clearRequested),
                            ) {
                                Text(if (field.clearRequested) "Undo clear" else "Clear stored")
                            }
                        }
                    }
                }
                state.configFields.forEach { field ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(field.path, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text =
                                when {
                                    field.clearRequested -> "Stored value will be cleared on save."
                                    field.storedValue != null -> "Current saved value loaded below."
                                    else -> "No saved value yet."
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = field.draftValue,
                            onValueChange = { value -> onConfigChanged(field.path, value) },
                            label = { Text("Config value") },
                            enabled = !state.loading && !state.saving,
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = { onConfigClear(field.path) },
                                enabled = !state.loading && !state.saving && (field.storedValue != null || field.draftValue.isNotEmpty() || field.clearRequested),
                            ) {
                                Text(if (field.clearRequested) "Undo clear" else "Clear stored")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !state.loading && !state.saving && state.hasPendingChanges,
            ) {
                Text(if (state.saving) "Saving..." else "Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.saving,
            ) {
                Text("Close")
            }
        },
    )
}

private fun SkillSnapshot.hasConfigSurface(): Boolean = secretStatuses.isNotEmpty() || configStatuses.isNotEmpty()

private fun SkillSnapshot.statusSummary(
    label: String,
    statuses: Map<String, Boolean>,
): String? {
    if (statuses.isEmpty()) {
        return null
    }
    val configuredCount = statuses.count { it.value }
    val missing = statuses.filterValues { configured -> !configured }.keys.sorted()
    return buildString {
        append(label)
        append(": ")
        append(configuredCount)
        append('/')
        append(statuses.size)
        append(" configured")
        if (missing.isNotEmpty()) {
            append(". Missing: ")
            append(missing.joinToString())
        }
    }
}

private fun SkillSnapshot.sourceLabel(): String =
    when (sourceType) {
        SkillSourceType.Bundled -> "Bundled"
        SkillSourceType.Local -> "Local import (untrusted)"
        SkillSourceType.Workspace -> "Workspace import (untrusted) ${workspaceSessionId.orEmpty()}".trim()
    }
