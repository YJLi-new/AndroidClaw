package ai.androidclaw.feature.settings

import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.runtime.providers.NetworkStatusProvider
import ai.androidclaw.runtime.providers.NetworkStatusSnapshot
import ai.androidclaw.testutil.MainDispatcherRule
import ai.androidclaw.testutil.InMemoryProviderSecretStore
import ai.androidclaw.testutil.buildTestProviderRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
    private val networkStatusProvider = object : NetworkStatusProvider {
        override fun currentStatus(): NetworkStatusSnapshot {
            return NetworkStatusSnapshot(
                supported = true,
                isConnected = true,
                isValidated = true,
                isMetered = false,
            )
        }
    }

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
        val state = waitForState(viewModel) { it.activeProviderId == "fake" }

        assertTrue(state.configured)
        assertEquals("fake", state.activeProviderId)
    }

    @Test
    fun `remote provider remains unconfigured without a stored or drafted api key`() = runTest {
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot().withEndpointSettings(
                ProviderType.Gemini,
                ProviderEndpointSettings(
                    baseUrl = ProviderType.Gemini.defaultBaseUrl,
                    modelId = "gemini-2.0-flash",
                    timeoutSeconds = 60,
                ),
            ).copy(providerType = ProviderType.Gemini),
        )
        val viewModel = buildViewModel()
        val state = waitForState(viewModel) { it.providerType == ProviderType.Gemini }

        assertFalse(state.configured)
    }

    @Test
    fun `saving provider settings persists selected provider settings and api key`() = runTest {
        val viewModel = buildViewModel()

        viewModel.selectProviderType(ProviderType.Anthropic)
        val selected = waitForState(viewModel) { it.activeProviderId == "anthropic" }
        assertEquals("anthropic", selected.activeProviderId)

        viewModel.onBaseUrlChanged("https://api.anthropic.com/v1")
        viewModel.onModelIdChanged("claude-sonnet-4-5")
        viewModel.onTimeoutChanged("15")
        viewModel.onApiKeyChanged("sk-ant-test")
        viewModel.save()
        val state = waitForState(viewModel) {
            it.providerType == ProviderType.Anthropic &&
                it.statusMessage == "Provider settings saved."
        }
        val storedSettings = settingsDataStore.settings.first()
        val anthropicSettings = storedSettings.endpointSettings(ProviderType.Anthropic)

        assertTrue(state.configured)
        assertEquals(ProviderType.Anthropic, storedSettings.providerType)
        assertEquals("https://api.anthropic.com/v1", anthropicSettings.baseUrl)
        assertEquals("claude-sonnet-4-5", anthropicSettings.modelId)
        assertEquals(15, anthropicSettings.timeoutSeconds)
        assertEquals(
            "sk-ant-test",
            secretStore.readApiKey(ProviderType.Anthropic),
        )
    }

    @Test
    fun `switching providers loads stored config for each provider`() = runTest {
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot()
                .withEndpointSettings(
                    ProviderType.OpenAiCompatible,
                    ProviderEndpointSettings(
                        baseUrl = "https://openai.example/v1",
                        modelId = "gpt-test",
                        timeoutSeconds = 30,
                    ),
                )
                .withEndpointSettings(
                    ProviderType.Glm,
                    ProviderEndpointSettings(
                        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                        modelId = "glm-4.5",
                        timeoutSeconds = 45,
                    ),
                ),
        )
        val viewModel = buildViewModel()

        viewModel.selectProviderType(ProviderType.Glm)
        val glmState = waitForState(viewModel) {
            it.providerType == ProviderType.Glm && it.modelId == "glm-4.5"
        }
        assertEquals("glm-4.5", glmState.modelId)

        viewModel.selectProviderType(ProviderType.OpenAiCompatible)
        val openAiState = waitForState(viewModel) {
            it.providerType == ProviderType.OpenAiCompatible && it.modelId == "gpt-test"
        }
        assertEquals("gpt-test", openAiState.modelId)
    }

    @Test
    fun `clearing a stored api key updates configuration state for selected provider`() = runTest {
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot().withEndpointSettings(
                ProviderType.Kimi,
                ProviderEndpointSettings(
                    baseUrl = ProviderType.Kimi.defaultBaseUrl,
                    modelId = "moonshot-v1-8k",
                    timeoutSeconds = 60,
                ),
            ).copy(providerType = ProviderType.Kimi),
        )
        secretStore.writeApiKey(ProviderType.Kimi, "sk-existing")
        val viewModel = buildViewModel()
        waitForState(viewModel) { it.providerType == ProviderType.Kimi && it.hasStoredApiKey }

        viewModel.clearStoredApiKey()
        val cleared = waitForState(viewModel) {
            it.providerType == ProviderType.Kimi &&
                !it.hasStoredApiKey &&
                it.statusMessage == "Stored API key cleared."
        }

        assertFalse(cleared.configured)
        assertNull(secretStore.readApiKey(ProviderType.Kimi))
    }

    @Test
    fun `provider recovery notice is surfaced when encrypted api key cannot be restored`() = runTest {
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot()
                .withEndpointSettings(
                    ProviderType.Anthropic,
                    ProviderEndpointSettings(
                        baseUrl = ProviderType.Anthropic.defaultBaseUrl,
                        modelId = "claude-sonnet-4-5",
                        timeoutSeconds = 60,
                    ),
                )
                .copy(providerType = ProviderType.Anthropic),
        )
        secretStore.markRecoveryNotice(ProviderType.Anthropic)

        val viewModel = buildViewModel()
        val state = waitForState(viewModel) {
            it.providerType == ProviderType.Anthropic &&
                it.statusMessage?.contains("could not be restored", ignoreCase = true) == true
        }

        assertEquals(
            "Stored API key could not be restored on this device. Please enter it again.",
            state.statusMessage,
        )
        assertFalse(state.hasStoredApiKey)
    }

    private fun buildViewModel(): SettingsViewModel {
        return SettingsViewModel(
            providerRegistry = buildTestProviderRegistry(),
            settingsDataStore = settingsDataStore,
            providerSecretStore = secretStore,
            networkStatusProvider = networkStatusProvider,
        )
    }

    private fun TestScope.waitForState(
        viewModel: SettingsViewModel,
        predicate: (SettingsUiState) -> Boolean,
    ): SettingsUiState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var lastState = viewModel.state.value
        while (System.nanoTime() < deadline) {
            testScheduler.advanceUntilIdle()
            val state = viewModel.state.value
            lastState = state
            if (predicate(state)) {
                return state
            }
            Thread.sleep(10)
        }
        error("Timed out waiting for state. Last state=$lastState")
    }
}
