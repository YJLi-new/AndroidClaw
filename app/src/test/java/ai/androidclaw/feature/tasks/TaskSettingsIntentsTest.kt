package ai.androidclaw.feature.tasks

import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskSettingsIntentsTest {
    @Test
    fun `notification settings intent targets current package`() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()

        val intent = buildNotificationSettingsIntent(application)

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(application.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `exact alarm settings intent targets app package on supported api levels`() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()

        val intent = buildExactAlarmSettingsIntent(application)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
            assertEquals("package:${application.packageName}", intent.dataString)
        } else {
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            assertTrue(intent.dataString?.startsWith("package:") == true)
        }
    }
}
