package ai.androidclaw.runtime.scheduler

import android.app.AlarmManager
import android.app.Application
import android.os.Build
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class SchedulerCoordinator(
    application: Application,
    private val clock: Clock,
) {
    private val alarmManager = application.getSystemService(AlarmManager::class.java)

    fun capabilities(): SchedulerCapabilities {
        return SchedulerCapabilities(
            minimumBackgroundInterval = Duration.ofMinutes(15),
            supportsExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager?.canScheduleExactAlarms() == true
            } else {
                true
            },
            supportedKinds = listOf("once", "interval", "cron"),
        )
    }

    fun nextRunPreview(expression: String, zoneId: ZoneId = ZoneId.systemDefault()): Instant? {
        return NextRunCalculator.computeNextRun(
            schedule = TaskSchedule.Cron(
                expression = CronExpression.parse(expression),
                zoneId = zoneId,
            ),
            after = clock.instant(),
        )
    }
}

