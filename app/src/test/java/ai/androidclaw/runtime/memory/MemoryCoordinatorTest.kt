package ai.androidclaw.runtime.memory

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.repository.MemoryRepository
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryCoordinatorTest {
    private lateinit var database: AndroidClawDatabase
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var coordinator: MemoryCoordinator

    @Before
    fun setUp() =
        runTest {
            val application = ApplicationProvider.getApplicationContext<android.app.Application>()
            database = buildTestDatabase(application)
            settingsDataStore = SettingsDataStore(application)
            settingsDataStore.setMemoryEnabled(false)
            memoryRepository = MemoryRepository(database.memoryItemDao())
            coordinator = MemoryCoordinator(settingsDataStore, memoryRepository)
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.setMemoryEnabled(false)
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            memoryRepository.clear(ownerUserId)
            database.close()
        }

    @Test
    fun `disabled memory does not retrieve or capture`() =
        runTest {
            val ownerUserId = settingsDataStore.memorySettingsSnapshot().installUserId
            memoryRepository.remember(ownerUserId, "User prefers green send buttons.")

            assertEquals(emptyList<String>(), coordinator.loadRelevantMemoryTexts("green buttons"))

            coordinator.captureTurn(
                sessionId = "session-1",
                userMessage = "Please remember that I prefer compact layouts.",
                assistantMessage = "Noted.",
                sourceMessageIds = listOf("u1", "a1"),
            )

            assertEquals(1, memoryRepository.countActive(ownerUserId))
        }

    @Test
    fun `enabled memory retrieves and captures local facts`() =
        runTest {
            settingsDataStore.setMemoryEnabled(true)

            coordinator.captureTurn(
                sessionId = "session-1",
                userMessage = "Please remember that I prefer compact Kotlin UI.",
                assistantMessage = "Noted.",
                sourceMessageIds = listOf("u1", "a1"),
            )

            val memories = coordinator.loadRelevantMemoryTexts("What Kotlin UI do I prefer?")

            assertTrue(memories.any { it.contains("compact Kotlin UI") })
        }

    @Test
    fun `extractor captures conservative user facts`() {
        val facts =
            LocalMemoryExtractor.extractFacts(
                userMessage = "My name is Alex. I live in Seattle. I use Android Studio.",
                assistantMessage = "Created the task.",
            )

        assertTrue(facts.any { it == "User's name is Alex" })
        assertTrue(facts.any { it == "User lives in Seattle" })
        assertTrue(facts.any { it == "User uses Android Studio" })
        assertTrue(facts.any { it.contains("Created the task", ignoreCase = true) })
    }
}
