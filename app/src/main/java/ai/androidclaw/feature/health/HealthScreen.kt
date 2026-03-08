package ai.androidclaw.feature.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.androidclaw.app.AppContainer

@Composable
fun HealthScreen(container: AppContainer) {
    val capabilities = container.schedulerCoordinator.capabilities()
    val tools = container.toolRegistry.descriptors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Health", style = MaterialTheme.typography.headlineSmall)
        HealthCard(
            title = "Provider",
            body = "Active provider: ${container.providerRegistry.defaultProvider.id}",
        )
        HealthCard(
            title = "Scheduler",
            body = "Kinds: ${capabilities.supportedKinds.joinToString()} | Exact alarms available: ${capabilities.supportsExactAlarms}",
        )
        HealthCard(
            title = "Tool registry",
            body = tools.joinToString { it.name },
        )
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
