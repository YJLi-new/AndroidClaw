package ai.androidclaw.feature.tasks

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
import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.NextRunCalculator
import ai.androidclaw.runtime.scheduler.TaskSchedule
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TasksScreen(container: AppContainer) {
    val capabilities = container.schedulerCoordinator.capabilities()
    val nextDaily = container.schedulerCoordinator.nextRunPreview("@daily")
    val nextWeekday = NextRunCalculator.computeNextRun(
        schedule = TaskSchedule.Cron(
            expression = CronExpression.parse("0 9 * * 1-5"),
            zoneId = ZoneId.systemDefault(),
        ),
        after = java.time.Instant.now(),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Scheduler Skeleton", style = MaterialTheme.typography.headlineSmall)
        SchedulerCard(
            title = "Supported kinds",
            body = capabilities.supportedKinds.joinToString(),
        )
        SchedulerCard(
            title = "Minimum background interval",
            body = "${capabilities.minimumBackgroundInterval.toMinutes()} minutes",
        )
        SchedulerCard(
            title = "Next @daily preview",
            body = nextDaily?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "Unavailable",
        )
        SchedulerCard(
            title = "Next 9am weekday cron preview",
            body = nextWeekday?.let(DateTimeFormatter.ISO_INSTANT::format) ?: "Unavailable",
        )
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

