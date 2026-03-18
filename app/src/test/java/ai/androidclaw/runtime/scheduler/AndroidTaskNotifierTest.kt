package ai.androidclaw.runtime.scheduler

import ai.androidclaw.data.model.Task
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class AndroidTaskNotifierTest {
    private lateinit var application: android.app.Application
    private lateinit var notifier: AndroidTaskNotifier
    private lateinit var notificationManager: android.app.NotificationManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        notifier = AndroidTaskNotifier(application)
        notificationManager = requireNotNull(application.getSystemService(android.app.NotificationManager::class.java))
        notificationManager.cancelAll()
    }

    @Test
    fun `success notification uses results channel`() {
        notifier.notifyTaskSucceeded(
            task = testTask(name = "Daily check"),
            taskRunId = "run-success",
            trigger = TaskTrigger.Scheduled,
            summary = "Finished cleanly.",
            nextRunAt = Instant.parse("2026-03-19T00:00:00Z"),
        )

        val shadowManager = shadowOf(notificationManager)
        val notifications = shadowManager.allNotifications

        assertTrue(shadowManager.notificationChannels.any { it.id == TASK_RESULTS_NOTIFICATION_CHANNEL_ID })
        assertEquals(1, notifications.size)
        assertEquals(TASK_RESULTS_NOTIFICATION_CHANNEL_ID, notifications.single().channelId)
        assertEquals("Task completed: Daily check", notifications.single().extras.getString("android.title"))
    }

    @Test
    fun `failure notification uses failures channel`() {
        notifier.notifyTaskFailed(
            task = testTask(name = "Daily check"),
            taskRunId = "run-failure",
            trigger = TaskTrigger.Manual,
            errorCode = "NETWORK_UNAVAILABLE",
            errorMessage = "Network unavailable.",
            nextRunAt = Instant.parse("2026-03-19T01:00:00Z"),
        )

        val shadowManager = shadowOf(notificationManager)
        val notifications = shadowManager.allNotifications

        assertTrue(shadowManager.notificationChannels.any { it.id == TASK_FAILURES_NOTIFICATION_CHANNEL_ID })
        assertEquals(1, notifications.size)
        assertEquals(TASK_FAILURES_NOTIFICATION_CHANNEL_ID, notifications.single().channelId)
        assertEquals("Task failed: Daily check", notifications.single().extras.getString("android.title"))
        assertTrue(
            notifications
                .single()
                .extras
                .getString("android.text")
                .orEmpty()
                .contains("Run now failed."),
        )
    }
}

private fun testTask(name: String): Task {
    val now = Instant.parse("2026-03-18T00:00:00Z")
    return Task(
        id = "task-1",
        name = name,
        prompt = "Check status",
        schedule = TaskSchedule.Once(now.plusSeconds(300)),
        executionMode = TaskExecutionMode.MainSession,
        targetSessionId = null,
        enabled = true,
        precise = false,
        nextRunAt = now.plusSeconds(300),
        lastRunAt = null,
        failureCount = 0,
        maxRetries = 3,
        createdAt = now,
        updatedAt = now,
    )
}
