package ai.androidclaw.feature.skills

import ai.androidclaw.R
import ai.androidclaw.runtime.skills.SkillEligibilityStatus
import ai.androidclaw.runtime.skills.SkillResolutionState
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.skills.SkillSourceType
import ai.androidclaw.ui.components.ClawEmptyState
import ai.androidclaw.ui.components.ClawIconButton
import ai.androidclaw.ui.components.ClawStatusBadge
import ai.androidclaw.ui.components.ClawTopBar
import ai.androidclaw.ui.components.NoticeTone
import ai.androidclaw.ui.text.asString
import ai.androidclaw.ui.text.resolve
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal val SKILL_IMPORT_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed")

@Composable
fun SkillsScreen(
    viewModel: SkillsViewModel,
    onOpenSkill: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val importLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.importLocalSkills(uri)
            }
        }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    LaunchedEffect(state.statusMessage) {
        val message = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.resolve(context))
        viewModel.clearStatusMessage()
    }

    Scaffold(
        modifier = Modifier.testTag("skillsScreen"),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ClawTopBar(
                title = stringResource(R.string.skills_title),
                titleTestTag = "skillsHeading",
                actions = {
                    ClawIconButton(
                        iconRes = R.drawable.ic_refresh,
                        contentDescription = stringResource(R.string.skills_action_refresh),
                        onClick = viewModel::refresh,
                        enabled = !state.isImporting,
                    )
                    ClawIconButton(
                        iconRes = R.drawable.ic_upload,
                        contentDescription = stringResource(R.string.skills_action_import),
                        onClick = { importLauncher.launch(SKILL_IMPORT_MIME_TYPES) },
                        enabled = !state.isImporting,
                        modifier = Modifier.testTag("skillsImportButton"),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            if (state.loading || state.isImporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!state.loading && state.skills.isEmpty()) {
                ClawEmptyState(
                    iconRes = R.drawable.ic_nav_skills,
                    title = stringResource(R.string.skills_empty_title),
                    body = stringResource(R.string.skills_empty_body),
                    action = {
                        TextButton(onClick = { importLauncher.launch(SKILL_IMPORT_MIME_TYPES) }) {
                            Text(stringResource(R.string.skills_action_import))
                        }
                    },
                )
            }
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag("skillsList"),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(state.skills, key = { it.id }) { skill ->
                    SkillRow(
                        skill = skill,
                        onOpen = { onOpenSkill(skill.id) },
                        onToggle = { enabled -> viewModel.toggleSkill(skillId = skill.id, enabled = enabled) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }

    SkillDialogs(state = state, viewModel = viewModel)
}

@Composable
private fun SkillRow(
    skill: SkillSnapshot,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val toggleLabel = stringResource(R.string.skills_toggle_description, skill.displayName)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                .testTag("skillRow-${skill.skillKey}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = skill.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val description = skill.frontmatter?.description ?: skill.parseError
            description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SkillSourceBadge(skill.sourceType)
                SkillStatusBadge(skill)
            }
        }
        Switch(
            checked = skill.enabled,
            onCheckedChange = onToggle,
            modifier =
                Modifier
                    .padding(start = 8.dp)
                    .testTag("skillToggle-${skill.skillKey}")
                    .semantics { contentDescription = toggleLabel },
        )
    }
}

@Composable
internal fun SkillSourceBadge(sourceType: SkillSourceType) {
    ClawStatusBadge(
        text =
            stringResource(
                when (sourceType) {
                    SkillSourceType.Bundled -> R.string.skills_badge_bundled
                    SkillSourceType.Local -> R.string.skills_badge_local
                    SkillSourceType.Workspace -> R.string.skills_badge_workspace
                },
            ),
        tone = if (sourceType == SkillSourceType.Bundled) NoticeTone.Info else NoticeTone.Warning,
    )
}

@Composable
internal fun SkillStatusBadge(skill: SkillSnapshot) {
    when {
        skill.resolutionState == SkillResolutionState.Shadowed ->
            ClawStatusBadge(text = stringResource(R.string.skills_badge_shadowed), tone = NoticeTone.Info)
        skill.eligibility.status != SkillEligibilityStatus.Eligible ->
            ClawStatusBadge(text = eligibilityLabel(skill.eligibility.status), tone = NoticeTone.Error)
        skill.secretStatuses.values.any { !it } || skill.configStatuses.values.any { !it } ->
            ClawStatusBadge(text = stringResource(R.string.skills_badge_needs_setup), tone = NoticeTone.Warning)
    }
}

@Composable
internal fun eligibilityLabel(status: SkillEligibilityStatus): String =
    stringResource(
        when (status) {
            SkillEligibilityStatus.Eligible -> R.string.skills_eligibility_ready
            SkillEligibilityStatus.Invalid -> R.string.skills_eligibility_invalid
            SkillEligibilityStatus.MissingTool -> R.string.skills_eligibility_missing_tool
            SkillEligibilityStatus.BridgeOnly -> R.string.skills_eligibility_bridge_only
        },
    )

/** Dialogs shared by the list and the detail screen. */
@Composable
internal fun SkillDialogs(
    state: SkillsUiState,
    viewModel: SkillsViewModel,
) {
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
private fun SkillEnableConfirmationDialog(
    state: SkillEnableConfirmationState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("skillEnableConfirmation"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.displayName, style = MaterialTheme.typography.titleSmall)
                Text(state.sourceLabel.asString(), style = MaterialTheme.typography.bodyMedium)
                Text(state.dispatchSummary.asString(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = state.scopeSummary.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.skills_confirm_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("skillEnableConfirm")) {
                Text(stringResource(R.string.skills_action_enable))
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
internal fun SmallProgress() {
    Box(modifier = Modifier.padding(16.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}
