package ai.androidclaw.feature.skills

import ai.androidclaw.runtime.skills.SkillResolutionState
import ai.androidclaw.runtime.skills.SkillSnapshot
import ai.androidclaw.runtime.skills.SkillSourceType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SkillsScreen(viewModel: SkillsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalSkills(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Skills", style = MaterialTheme.typography.headlineSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = viewModel::refresh,
                enabled = !state.isImporting,
            ) {
                Text("Refresh")
            }
            Button(
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                enabled = !state.isImporting,
            ) {
                Text(if (state.isImporting) "Importing..." else "Import zip")
            }
        }
        state.statusMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
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
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillSnapshot,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(skill.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Source: ${skill.sourceLabel()}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (skill.resolutionState == SkillResolutionState.Shadowed) {
                        Text(
                            text = "Shadowed by ${skill.shadowedBy.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onToggle,
                )
            }
            Text(
                skill.frontmatter?.description ?: skill.parseError.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Eligibility: ${skill.eligibility.status}",
                style = MaterialTheme.typography.labelMedium,
            )
            if (skill.eligibility.reasons.isNotEmpty()) {
                Text(
                    text = skill.eligibility.reasons.joinToString(separator = "\n"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            skill.frontmatter?.commandTool?.let { commandTool ->
                Text(
                    text = "Dispatch tool: $commandTool",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (skill.skillKey != skill.displayName) {
                Text(
                    text = "Skill key: ${skill.skillKey}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun SkillSnapshot.sourceLabel(): String {
    return when (sourceType) {
        SkillSourceType.Bundled -> "Bundled"
        SkillSourceType.Local -> "Local"
        SkillSourceType.Workspace -> "Workspace ${workspaceSessionId.orEmpty()}".trim()
    }
}
