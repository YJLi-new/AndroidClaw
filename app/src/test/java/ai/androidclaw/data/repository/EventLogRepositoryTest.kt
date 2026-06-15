package ai.androidclaw.data.repository

import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.db.entity.EventLogEntity
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

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
    fun `log emits flow and trim removes older entries`() =
        runTest {
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

    @Test
    fun `unknown persisted event category and level stay observable`() =
        runTest {
            database.eventLogDao().insert(
                EventLogEntity(
                    id = "unknown-event",
                    timestamp = 10L,
                    category = "future-category",
                    level = "trace",
                    message = "Forward-compatible event",
                    detailsJson = "{\"raw\":true}",
                ),
            )

            val event = repository.observeRecent(limit = 10).first().single()

            assertEquals(EventCategory.System, event.category)
            assertEquals(EventLevel.Warn, event.level)
            assertEquals("Forward-compatible event", event.message)
            assertEquals("{\"raw\":true}", event.details)
        }

    @Test
    fun `log bounds oversized message and details before persistence`() =
        runTest {
            val oversizedMessage = "m".repeat(EVENT_LOG_MESSAGE_MAX_CHARS + 50)
            val oversizedDetails = "d".repeat(EVENT_LOG_DETAILS_MAX_CHARS + 50)

            repository.log(
                category = EventCategory.Tool,
                level = EventLevel.Warn,
                message = oversizedMessage,
                details = oversizedDetails,
            )

            val raw =
                database
                    .eventLogDao()
                    .getRecent(limit = 1)
                    .first()
                    .single()
            val event = repository.observeRecent(limit = 1).first().single()

            assertEquals(EVENT_LOG_MESSAGE_MAX_CHARS, raw.message.length)
            assertEquals(EVENT_LOG_DETAILS_MAX_CHARS, raw.detailsJson?.length)
            assertEquals(raw.message, event.message)
            assertEquals(raw.detailsJson, event.details)
            assertTrue(event.message.endsWith("…[truncated]"))
            assertTrue(event.details?.endsWith("…[truncated]") == true)
        }

    @Test
    fun `observeRecent bounds oversized legacy event rows`() =
        runTest {
            database.eventLogDao().insert(
                EventLogEntity(
                    id = "legacy-large-event",
                    timestamp = 10L,
                    category = "system",
                    level = "info",
                    message = "m".repeat(EVENT_LOG_MESSAGE_MAX_CHARS + 50),
                    detailsJson = "d".repeat(EVENT_LOG_DETAILS_MAX_CHARS + 50),
                ),
            )

            val event = repository.observeRecent(limit = 1).first().single()

            assertEquals(EVENT_LOG_MESSAGE_MAX_CHARS, event.message.length)
            assertEquals(EVENT_LOG_DETAILS_MAX_CHARS, event.details?.length)
            assertTrue(event.message.endsWith("…[truncated]"))
            assertTrue(event.details?.endsWith("…[truncated]") == true)
        }
}
