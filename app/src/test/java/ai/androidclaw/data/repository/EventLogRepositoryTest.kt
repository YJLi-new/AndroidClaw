package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.EventLogEntity
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import app.cash.turbine.test
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
        repository.observeRecent(limit = 10).test {
            emissions += awaitItem()

            repository.log(
                category = EventCategory.Provider,
                level = EventLevel.Error,
                message = "Provider offline",
                details = "{\"provider\":\"fake\"}",
            )

            emissions += awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

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

        val recent = repository.observeRecent(limit = 2).first()
        assertEquals(emptyList<ai.androidclaw.data.model.EventLogEntry>(), emissions.first())
        assertEquals(1, emissions.last().size)
        assertEquals(EventCategory.Provider, emissions.last().single().category)
        assertTrue(recent.first().message == "Provider offline")

        val trimmed = repository.trimOlderThan(Instant.ofEpochMilli(2L))
        assertEquals(1, trimmed)
        assertEquals(1, repository.count())
    }
}
