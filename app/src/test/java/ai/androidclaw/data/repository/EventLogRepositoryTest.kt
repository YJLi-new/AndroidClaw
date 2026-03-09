package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.EventLogEntity
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EventLogRepositoryTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var repository: EventLogRepository

    @Before
    fun setUp() {
        database = buildTestDatabase(ApplicationProvider.getApplicationContext())
        repository = EventLogRepository(database.eventLogDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `log emits flow and trim removes older entries`() = runTest {
        val emissions = mutableListOf<List<ai.androidclaw.data.model.EventLogEntry>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeRecent(limit = 10).take(2).toList(emissions)
        }
        runCurrent()

        repository.log(
            category = EventCategory.Provider,
            level = EventLevel.Error,
            message = "Provider offline",
            details = "{\"provider\":\"fake\"}",
        )

        job.join()

        database.eventLogDao().insert(
            EventLogEntity(
                id = "old",
                timestamp = 1L,
                category = "system",
                level = "info",
                message = "Old event",
                detailsJson = null,
            ),
        )

        val recent = repository.observeRecent(limit = 2).take(1).toList().single()
        assertEquals(emptyList<ai.androidclaw.data.model.EventLogEntry>(), emissions.first())
        assertEquals(1, emissions.last().size)
        assertEquals(EventCategory.Provider, emissions.last().single().category)
        assertTrue(recent.first().message == "Provider offline")

        val trimmed = repository.trimOlderThan(Instant.ofEpochMilli(2L))
        assertEquals(1, trimmed)
        assertEquals(1, repository.count())
    }
}
