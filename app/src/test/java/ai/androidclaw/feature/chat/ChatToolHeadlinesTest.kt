package ai.androidclaw.feature.chat

import ai.androidclaw.testutil.text
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ChatToolHeadlinesTest {
    private fun zh(
        toolName: String,
        headline: String,
    ): String? = localizedToolHeadline(toolName, headline, Locale.SIMPLIFIED_CHINESE)?.text()

    private fun en(
        toolName: String,
        headline: String,
    ): String? = localizedToolHeadline(toolName, headline, Locale.ENGLISH)?.text()

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `chinese headlines come from resources and keep the data`() {
        assertEquals(
            "本地时间 2026-11-03 20:37 星期二（GMT，UTC+00:00）",
            zh("device_status", "Local time 2026-11-03 20:37, Tuesday (GMT, UTC+00:00)."),
        )
        assertEquals(
            "还没有定时任务。",
            zh("tasks_list", "No scheduled tasks yet. Create one with tasks_create (once, interval of 15+ min, or cron)."),
        )
        assertEquals("2 个任务", zh("tasks_list", "2 task(s):"))
        assertEquals("已打开系统设置：storage。", zh("open_settings", "Opened storage settings."))
        assertEquals("已生成 1024×1024 的图片。", zh("image_generate", "Generated image a6ec0d24-a574-4a23-bf51-365e33180691, 1024×1024"))
        assertEquals("“Main session”中的 3 条消息", zh("sessions_history", "3 message(s) from \"Main session\" (id=s-1)."))
        assertEquals("notes/todo.md（25 个字符）", zh("workspace_read", "notes/todo.md (25 characters)."))
        assertEquals("已获取 https://example.com — Weather", zh("web_fetch", "Fetched https://example.com — Weather (120 chars, showing first 100)."))
        assertEquals("已开始 1m 30s 的倒计时（Tea）。", zh("set_timer", "Started a 1m 30s timer (Tea)."))
    }

    @Test
    fun `english headlines read like the runtime summary without model instructions or ids`() {
        assertEquals("Local time 2026-11-03 20:37, Tuesday (GMT, UTC+00:00).", en("device_status", "Local time 2026-11-03 20:37, Tuesday (GMT, UTC+00:00)."))
        assertEquals("No scheduled tasks yet.", en("tasks_list", "No scheduled tasks yet. Create one with tasks_create (once, interval of 15+ min, or cron)."))
        assertEquals("1 task", en("tasks.list", "1 task(s):"))
        assertEquals("Generated a 1024×1536 image.", en("image_generate", "Generated image img-1, 1024×1536"))
        assertEquals("Queued “Daily” to run now.", en("tasks_run_now", "Queued Daily to run now. Check tasks_runs for the result."))
        assertEquals("Opened the calendar with “Dentist” at 2026-03-10 15:00; save it there.", en("calendar_add_event", "Opened the calendar with \"Dentist\" at 2026-03-10T15:00; the user confirms it there."))
    }

    @Test
    fun `unknown tools, data lines and changed templates are left as they are`() {
        assertNull(en("memory_get", "Memory m-1 (manual, 2026-03-08T00:00:00Z): User likes tea"))
        assertNull(en("some_skill_tool", "No scheduled tasks yet."))
        assertNull(en("tasks_list", "Nothing scheduled."))
        assertNull(en("", "Opened storage settings."))
        assertNull(en("open_settings", "Opened " + "x".repeat(2_000) + " settings."))
    }
}
