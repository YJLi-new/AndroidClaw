package ai.androidclaw.feature.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SkillsScreen(viewModel: SkillsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bundled Skills", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = viewModel::refresh) {
            Text("Refresh")
        }
        if (state.loading) {
            Text("Loading bundled SKILL.md files...")
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.skills, key = { it.id }) { skill ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(skill.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            skill.frontmatter?.description ?: skill.parseError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Eligibility: ${skill.eligibility.status}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (skill.eligibility.reasons.isNotEmpty()) {
                            Text(skill.eligibility.reasons.joinToString(), style = MaterialTheme.typography.bodySmall)
                        }
                        skill.frontmatter?.commandTool?.let { commandTool ->
                            Text("Dispatch: $commandTool", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
