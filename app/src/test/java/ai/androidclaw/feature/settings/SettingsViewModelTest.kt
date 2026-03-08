package ai.androidclaw.feature.settings

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.runtime.providers.FakeProvider
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.testutil.MainDispatcherRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        settingsDataStore = SettingsDataStore(application)
        settingsDataStore.setProviderType("fake")
    }

    @After
    fun tearDown() = runTest {
        settingsDataStore.setProviderType("fake")
    }

    @Test
    fun `fake provider is considered configured`() = runTest {
        val viewModel = buildViewModel()

        val state = viewModel.state.first { it.providerType == "fake" }

        assertTrue(state.configured)
        assertTrue(state.activeProviderId == "fake")
    }

    @Test
    fun `non fake provider stays unconfigured until later milestone`() = runTest {
        settingsDataStore.setProviderType("openai")
        val viewModel = buildViewModel()

        val state = viewModel.state.first { it.providerType == "openai" }

        assertFalse(state.configured)
    }

    private fun buildViewModel(): SettingsViewModel {
        return SettingsViewModel(
            providerRegistry = ProviderRegistry(
                defaultProvider = FakeProvider(
                    clock = Clock.fixed(Instant.parse("2026-03-08T00:00:00Z"), ZoneOffset.UTC),
                ),
            ),
            settingsDataStore = settingsDataStore,
        )
    }
}
