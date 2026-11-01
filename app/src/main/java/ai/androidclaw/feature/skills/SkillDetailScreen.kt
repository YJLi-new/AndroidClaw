package ai.androidclaw.feature.skills

import ai.androidclaw.R
import ai.androidclaw.feature.chat.ChatRichText
import ai.androidclaw.runtime.skills.SkillCommandDispatch
import ai.androidclaw.runtime.skills.SkillResolutionState
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.ui.components.ClawCard
import ai.androidclaw.ui.components.ClawFactRow
import ai.androidclaw.ui.components.ClawNotice
import ai.androidclaw.ui.components.ClawSectionHeader
import ai.androidclaw.ui.components.ClawTopBar
import ai.androidclaw.ui.components.NoticeTone
import ai.androidclaw.ui.text.asString
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SkillDetailScreen(
    viewModel: SkillsViewModel,
    skillId: String,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val skill = state.skills.firstOrNull { it.id == skillId }

    Scaffold(
        modifier = Modifier.testTag("skillDetailScreen"),
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            ClawTopBar(
                title = skill?.displayName ?: stringResource(R.string.skills_detail_title),
                titleTestTag = "skillDetailHeading",
                onBack = onBack,
            )
        },
    ) { padding ->
        if (skill == null) {
            if (state.loading) {
                SmallProgress()
            } else {
                Text(
                    text = stringResource(R.string.skills_detail_missing),
                    modifier = Modifier.padding(padding).padding(16.dp),
                )
            }
            return@Scaffold
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            SkillSummary(skill = skill, onToggle = { viewModel.toggleSkill(skill.id, it) })
            if (skill.secretStatuses.isNotEmpty() || skill.configStatuses.isNotEmpty()) {
                OutlinedButton(
                    onClick = { viewModel.openConfiguration(skill) },
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .testTag("skillConfigureButton"),
                ) {
                    Text(stringResource(R.string.skills_action_configure))
                }
            }
            ClawSectionHeader(stringResource(R.string.skills_detail_instructions))
            ClawCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                val body = skill.instructionsMd.trim()
                if (body.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skills_detail_no_instructions),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ChatRichText(
                        text = body,
                        modifier =
                            Modifier
                                .padding(16.dp)
                                .testTag("skillInstructions"),
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
            skill.rawFrontmatter?.takeIf { it.isNotBlank() }?.let { raw ->
                ClawSectionHeader(stringResource(R.string.skills_detail_frontmatter))
                ClawCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = raw.trim(),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    SkillDialogs(state = state, viewModel = viewModel)
}

@Composable
private fun SkillSummary(
    skill: SkillSnapshot,
    onToggle: (Boolean) -> Unit,
) {
    val frontmatter = skill.frontmatter
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        frontmatter?.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SkillSourceBadge(skill.sourceType)
            SkillStatusBadge(skill)
            Text(
                text = stringResource(if (skill.enabled) R.string.skills_state_enabled else R.string.skills_state_disabled),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = skill.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("skillDetailToggle"),
            )
        }
        skill.parseError?.takeIf { it.isNotBlank() }?.let { error ->
            ClawNotice(text = error, tone = NoticeTone.Error)
        }
        if (skill.eligibility.reasons.isNotEmpty()) {
            ClawNotice(
                text = skill.eligibility.reasons.joinToString("\n"),
                tone = NoticeTone.Warning,
            )
        }
        if (skill.resolutionState == SkillResolutionState.Shadowed) {
            ClawNotice(
                text = stringResource(R.string.skills_detail_shadowed, skill.shadowedBy.orEmpty()),
                tone = NoticeTone.Info,
            )
        }
        if (frontmatter?.userInvocable == true) {
            ClawFactRow(
                label = stringResource(R.string.skills_detail_command),
                value = "/${frontmatter.name}",
                modifier = Modifier.testTag("skillSlashCommand"),
            )
        }
        ClawFactRow(
            label = stringResource(R.string.skills_detail_dispatch),
            value =
                when {
                    frontmatter?.commandDispatch == SkillCommandDispatch.Tool && !frontmatter.commandTool.isNullOrBlank() ->
                        stringResource(R.string.skills_detail_dispatch_tool, frontmatter.commandTool.orEmpty())
                    else -> stringResource(R.string.skills_detail_dispatch_model)
                },
        )
        ClawFactRow(label = stringResource(R.string.skills_detail_source), value = skill.sourceLabel().asString())
        ClawFactRow(label = stringResource(R.string.skills_detail_eligibility), value = stringResource(skillStateLabelRes(skill.enabled, skill.eligibility.status)))
        if (skill.skillKey != skill.displayName) {
            ClawFactRow(label = stringResource(R.string.skills_detail_key), value = skill.skillKey)
        }
        statusLine(R.string.skills_detail_secrets, skill.secretStatuses)?.let { (label, value) ->
            ClawFactRow(label = label, value = value)
        }
        statusLine(R.string.skills_detail_config, skill.configStatuses)?.let { (label, value) ->
            ClawFactRow(label = label, value = value)
        }
        frontmatter?.homepage?.takeIf { it.isNotBlank() }?.let { homepage ->
            ClawFactRow(label = stringResource(R.string.skills_detail_homepage), value = homepage)
        }
    }
}

@Composable
private fun statusLine(
    labelRes: Int,
    statuses: Map<String, Boolean>,
): Pair<String, String>? {
    if (statuses.isEmpty()) return null
    val configured = statuses.count { it.value }
    val missing = statuses.filterValues { !it }.keys.sorted()
    val value =
        if (missing.isEmpty()) {
            stringResource(R.string.skills_detail_configured_all, configured, statuses.size)
        } else {
            stringResource(R.string.skills_detail_configured_missing, configured, statuses.size, missing.joinToString())
        }
    return stringResource(labelRes) to value
}
