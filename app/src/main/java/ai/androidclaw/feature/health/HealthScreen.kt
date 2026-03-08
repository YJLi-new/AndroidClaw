package ai.androidclaw.feature.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HealthScreen(viewModel: HealthViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Health", style = MaterialTheme.typography.headlineSmall)
        HealthCard(
            title = "Provider",
            body = "Active provider: ${state.providerId}",
        )
        HealthCard(
            title = "Scheduler",
            body = "Kinds: ${state.supportedKinds.joinToString()} | Exact alarms available: ${state.supportsExactAlarms}",
        )
        HealthCard(
            title = "Tool registry",
            body = state.tools.joinToString(),
        )
        if (state.recentEvents.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.recentEvents, key = { it.id }) { event ->
                    HealthCard(
                        title = "${event.category} ${event.level}",
                        body = event.message,
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
