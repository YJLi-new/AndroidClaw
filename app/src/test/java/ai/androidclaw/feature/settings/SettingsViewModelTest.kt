package ai.androidclaw.feature.settings

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.testutil.MainDispatcherRule
import ai.androidclaw.testutil.InMemoryProviderSecretStore
import ai.androidclaw.testutil.buildTestProviderRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private lateinit var secretStore: InMemoryProviderSecretStore

    @Before
    fun setUp() = runTest {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        settingsDataStore = SettingsDataStore(application)
        secretStore = InMemoryProviderSecretStore()
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
    }

    @After
    fun tearDown() = runTest {
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        secretStore.clear()
    }

    @Test
    fun `fake provider is considered configured`() = runTest {
        val viewModel = buildViewModel()

        val state = viewModel.state.first {
            it.providerType == ProviderType.Fake && it.activeProviderId == "fake"
        }

        assertTrue(state.configured)
        assertEquals("fake", state.activeProviderId)
    }

    @Test
    fun `openai compatible provider remains unconfigured without a stored or drafted api key`() = runTest {
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot(
                providerType = ProviderType.OpenAiCompatible,
                openAiModelId = "gpt-test",
            ),
        )
        val viewModel = buildViewModel()

        val state = viewModel.state.first { it.providerType == ProviderType.OpenAiCompatible }

        assertFalse(state.configured)
    }

    @Test
    fun `saving provider settings persists typed settings and api key`() = runTest {
        val viewModel = buildViewModel()

        viewModel.selectProviderType(ProviderType.OpenAiCompatible)
        viewModel.onOpenAiBaseUrlChanged("https://example.test/v1")
        viewModel.onOpenAiModelIdChanged("gpt-test")
        viewModel.onOpenAiTimeoutChanged("15")
        viewModel.onApiKeyChanged("sk-test")
        viewModel.save()

        val state = viewModel.state.first { it.statusMessage == "Provider settings saved." }
        val storedSettings = settingsDataStore.settings.first()

        assertTrue(state.configured)
        assertEquals(ProviderType.OpenAiCompatible, storedSettings.providerType)
        assertEquals("https://example.test/v1", storedSettings.openAiBaseUrl)
        assertEquals("gpt-test", storedSettings.openAiModelId)
        assertEquals(15, storedSettings.openAiTimeoutSeconds)
        assertEquals(
            "sk-test",
            secretStore.readApiKey(ProviderType.OpenAiCompatible),
        )
    }

    @Test
    fun `clearing a stored api key updates configuration state`() = runTest {
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot(
                providerType = ProviderType.OpenAiCompatible,
                openAiModelId = "gpt-test",
            ),
        )
        secretStore.writeApiKey(ProviderType.OpenAiCompatible, "sk-existing")
        val viewModel = buildViewModel()

        viewModel.state.first { it.hasStoredApiKey }
        viewModel.clearStoredApiKey()

        val cleared = viewModel.state.first {
            !it.hasStoredApiKey && it.statusMessage == "Stored API key cleared."
        }

        assertFalse(cleared.configured)
        assertNull(secretStore.readApiKey(ProviderType.OpenAiCompatible))
    }

    private fun buildViewModel(): SettingsViewModel {
        return SettingsViewModel(
            providerRegistry = buildTestProviderRegistry(),
            settingsDataStore = settingsDataStore,
            providerSecretStore = secretStore,
        )
    }
}
