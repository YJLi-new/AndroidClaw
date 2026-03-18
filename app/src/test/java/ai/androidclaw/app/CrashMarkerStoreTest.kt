package ai.androidclaw.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrashMarkerStoreTest {
    private lateinit var crashMarkerStore: CrashMarkerStore

    @Before
    fun setUp() {
        crashMarkerStore = CrashMarkerStore(ApplicationProvider.getApplicationContext())
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
}
