package ai.androidclaw.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class CrashMarkerStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var crashMarkerStore: CrashMarkerStore

    @Before
    fun setUp() {
        crashMarkerStore = CrashMarkerStore(context)
        crashMarkerStore.clear()
    }

    @Test
    fun `record persists and read returns the last crash marker`() {
        crashMarkerStore.record(
            threadName = "main",
            throwable = IllegalArgumentException("bad input"),
            timestamp = Instant.parse("2026-03-18T10:00:00Z"),
        )

        val marker = crashMarkerStore.read()

        assertNotNull(marker)
        assertEquals("main", marker?.threadName)
        assertEquals("java.lang.IllegalArgumentException", marker?.exceptionType)
        assertEquals("bad input", marker?.message)
        assertEquals(Instant.parse("2026-03-18T10:00:00Z"), marker?.timestamp)
        assertNotNull(marker?.stackTrace)
    }

    @Test
    fun `clear removes stored crash marker`() {
        crashMarkerStore.record(
            threadName = "main",
            throwable = IllegalStateException("boom"),
        )

        crashMarkerStore.clear()

        assertNull(crashMarkerStore.read())
    }

    @Test
    fun `record bounds crash marker fields before persistence and read`() {
        val longThreadName = " thread-" + "t".repeat(CRASH_MARKER_THREAD_NAME_MAX_CHARS + 20)
        val longMessage = " message-" + "m".repeat(CRASH_MARKER_STACKTRACE_MAX_CHARS + 200)

        crashMarkerStore.record(
            threadName = longThreadName,
            throwable = IllegalArgumentException(longMessage),
            timestamp = Instant.parse("2026-03-18T10:00:00Z"),
        )

        val marker = crashMarkerStore.read()

        assertEquals(longThreadName.trim().take(CRASH_MARKER_THREAD_NAME_MAX_CHARS), marker?.threadName)
        assertEquals(longMessage.trim().take(CRASH_MARKER_MESSAGE_MAX_CHARS), marker?.message)
        assertEquals(CRASH_MARKER_STACKTRACE_MAX_CHARS, marker?.stackTrace?.length)
    }

    @Test
    fun `read bounds oversized legacy crash marker`() {
        val legacyMarker =
            CrashMarker(
                timestampEpochMillis = Instant.parse("2026-03-18T10:00:00Z").toEpochMilli(),
                threadName = "legacy-thread-" + "t".repeat(CRASH_MARKER_THREAD_NAME_MAX_CHARS + 20),
                exceptionType = "legacy.exception." + "E".repeat(CRASH_MARKER_EXCEPTION_TYPE_MAX_CHARS + 20),
                message = "legacy-message-" + "m".repeat(CRASH_MARKER_MESSAGE_MAX_CHARS + 20),
                stackTrace = "legacy-stack".repeat(CRASH_MARKER_STACKTRACE_MAX_CHARS),
            )
        writeRawMarker(legacyMarker)

        val marker = crashMarkerStore.read()

        assertEquals(CRASH_MARKER_THREAD_NAME_MAX_CHARS, marker?.threadName?.length)
        assertEquals(CRASH_MARKER_EXCEPTION_TYPE_MAX_CHARS, marker?.exceptionType?.length)
        assertEquals(CRASH_MARKER_MESSAGE_MAX_CHARS, marker?.message?.length)
        assertEquals(CRASH_MARKER_STACKTRACE_MAX_CHARS, marker?.stackTrace?.length)
    }

    @Test
    fun `read normalizes blank legacy crash marker text`() {
        writeRawMarker(
            CrashMarker(
                timestampEpochMillis = Instant.parse("2026-03-18T10:00:00Z").toEpochMilli(),
                threadName = "   ",
                exceptionType = "   ",
                message = "   ",
                stackTrace = "legacy stack",
            ),
        )

        val marker = crashMarkerStore.read()

        assertEquals(CRASH_MARKER_UNKNOWN_THREAD, marker?.threadName)
        assertEquals(CRASH_MARKER_UNKNOWN_EXCEPTION, marker?.exceptionType)
        assertNull(marker?.message)
        assertEquals("legacy stack", marker?.stackTrace)
    }

    private fun writeRawMarker(marker: CrashMarker) {
        context
            .getSharedPreferences(CRASH_MARKER_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(CRASH_MARKER_KEY_LAST_CRASH, Json.encodeToString(CrashMarker.serializer(), marker))
            .commit()
    }
}
