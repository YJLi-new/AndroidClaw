package ai.androidclaw.feature.tasks

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
import java.time.format.DateTimeFormatter

@Composable
fun TasksScreen(viewModel: TasksViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tasks", style = MaterialTheme.typography.headlineSmall)
        SchedulerCard(
            title = "Supported kinds",
            body = state.capabilities.supportedKinds.joinToString(),
        )
        SchedulerCard(
            title = "Minimum background interval",
            body = "${state.capabilities.minimumBackgroundInterval.toMinutes()} minutes",
        )
        SchedulerCard(
            title = "Next @daily preview",
            body = state.nextDailyPreview?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "Unavailable",
        )
        SchedulerCard(
            title = "Next 9am weekday cron preview",
            body = state.nextWeekdayPreview?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "Unavailable",
        )
        if (state.tasks.isEmpty()) {
            SchedulerCard(
                title = "Saved tasks",
                body = "No tasks yet.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.tasks, key = { it.id }) { task ->
                    SchedulerCard(
                        title = task.name,
                        body = "Enabled: ${task.enabled} | Next: ${task.nextRunAt?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "Unscheduled"}",
                    )
                }
            }
        }
    }
}

@Composable
private fun SchedulerCard(title: String, body: String) {
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
