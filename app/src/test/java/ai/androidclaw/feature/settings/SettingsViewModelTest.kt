package ai.androidclaw.feature.settings

import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.ThemePreference
import ai.androidclaw.runtime.providers.NetworkStatusProvider
import ai.androidclaw.runtime.providers.NetworkStatusSnapshot
import ai.androidclaw.runtime.providers.OpenAiCompatibleProvider
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.providers.createProviderBaseHttpClient
import ai.androidclaw.testutil.FakeOpenAiCodexOAuthClient
import ai.androidclaw.testutil.InMemoryProviderSecretStore
import ai.androidclaw.testutil.MainDispatcherRule
import ai.androidclaw.testutil.buildTestProviderRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var secretStore: InMemoryProviderSecretStore
    private var currentNetworkStatus =
        NetworkStatusSnapshot(
            supported = true,
            isConnected = true,
            isValidated = true,
            isMetered = false,
        )
    private val networkStatusFlow = MutableStateFlow(currentNetworkStatus)
    private val networkStatusProvider =
        object : NetworkStatusProvider {
            override fun currentStatus(): NetworkStatusSnapshot = networkStatusFlow.value

            override fun observeStatus() = networkStatusFlow
        }
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var oAuthClient: FakeOpenAiCodexOAuthClient

    @Before
    fun setUp() =
        runTest {
            val application = ApplicationProvider.getApplicationContext<android.app.Application>()
            settingsDataStore = SettingsDataStore(application)
            secretStore = InMemoryProviderSecretStore()
            oAuthClient = FakeOpenAiCodexOAuthClient()
            currentNetworkStatus =
                NetworkStatusSnapshot(
                    supported = true,
                    isConnected = true,
                    isValidated = true,
                    isMetered = false,
                )
            networkStatusFlow.value = currentNetworkStatus
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            secretStore.clear()
        }

    @Test
    fun `fake provider is considered configured`() =
        runTest {
            val viewModel = buildViewModel()
            val state = waitForState(viewModel) { it.activeProviderId == "fake" }

            assertTrue(state.configured)
            assertEquals("fake", state.activeProviderId)
        }

    @Test
    fun `remote provider remains unconfigured without a stored or drafted api key`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
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
    fun `saving provider settings persists selected provider settings and api key`() =
        runTest {
            val viewModel = buildViewModel()

            viewModel.selectProviderType(ProviderType.Anthropic)
            val selected = waitForState(viewModel) { it.activeProviderId == "anthropic" }
            assertEquals("anthropic", selected.activeProviderId)

            viewModel.onBaseUrlChanged("https://api.anthropic.com/v1")
            viewModel.onModelIdChanged("claude-sonnet-4-5")
            viewModel.onTimeoutChanged("15")
            viewModel.onApiKeyChanged("sk-ant-test")
            viewModel.save()
            val state =
                waitForState(viewModel) {
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
    fun `switching providers loads stored config for each provider`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.OpenAiCompatible,
                        ProviderEndpointSettings(
                            baseUrl = "https://openai.example/v1",
                            modelId = "gpt-test",
                            timeoutSeconds = 30,
                        ),
                    ).withEndpointSettings(
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
            val glmState =
                waitForState(viewModel) {
                    it.providerType == ProviderType.Glm && it.modelId == "glm-4.5"
                }
            assertEquals("glm-4.5", glmState.modelId)

            viewModel.selectProviderType(ProviderType.OpenAiCompatible)
            val openAiState =
                waitForState(viewModel) {
                    it.providerType == ProviderType.OpenAiCompatible && it.modelId == "gpt-test"
                }
            assertEquals("gpt-test", openAiState.modelId)
        }

    @Test
    fun `clearing a stored api key updates configuration state for selected provider`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
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
            val cleared =
                waitForState(viewModel) {
                    it.providerType == ProviderType.Kimi &&
                        !it.hasStoredApiKey &&
                        it.statusMessage == "Stored API key cleared."
                }

            assertFalse(cleared.configured)
            assertNull(secretStore.readApiKey(ProviderType.Kimi))
        }

    @Test
    fun `openai codex sign in stores oauth credential and configures provider`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.OpenAiCodex,
                        ProviderEndpointSettings(
                            baseUrl = ProviderType.OpenAiCodex.defaultBaseUrl,
                            modelId = ProviderType.OpenAiCodex.defaultModelId,
                            timeoutSeconds = 60,
                        ),
                    ).copy(providerType = ProviderType.OpenAiCodex),
            )
            val viewModel = buildViewModel()
            waitForState(viewModel) { it.providerType == ProviderType.OpenAiCodex }

            viewModel.startOpenAiCodexDeviceCodeSignIn()

            val signedIn =
                waitForState(viewModel) {
                    it.providerType == ProviderType.OpenAiCodex &&
                        it.hasOAuthCredential &&
                        it.statusMessage == "OpenAI Codex sign-in complete."
                }

            assertTrue(signedIn.configured)
            assertEquals("codex@example.test", signedIn.oAuthProfileLabel)
            assertEquals(1, oAuthClient.loginCount)
            assertEquals("access-token", secretStore.readOAuthCredential(ProviderType.OpenAiCodex)?.accessToken)
        }

    @Test
    fun `clearing openai codex sign in updates configuration state`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.OpenAiCodex,
                        ProviderEndpointSettings(
                            baseUrl = ProviderType.OpenAiCodex.defaultBaseUrl,
                            modelId = ProviderType.OpenAiCodex.defaultModelId,
                            timeoutSeconds = 60,
                        ),
                    ).copy(providerType = ProviderType.OpenAiCodex),
            )
            secretStore.writeOAuthCredential(
                ProviderType.OpenAiCodex,
                ai.androidclaw.data.ProviderOAuthCredential(
                    provider = ProviderType.OpenAiCodex.providerId,
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    expiresAtEpochMillis = 1_800_000_000_000,
                    email = "codex@example.test",
                    profileName = "codex@example.test",
                ),
            )
            val viewModel = buildViewModel()
            waitForState(viewModel) { it.providerType == ProviderType.OpenAiCodex && it.hasOAuthCredential }

            viewModel.clearOpenAiCodexSignIn()
            val cleared =
                waitForState(viewModel) {
                    it.providerType == ProviderType.OpenAiCodex &&
                        !it.hasOAuthCredential &&
                        it.statusMessage == "OpenAI Codex sign-in cleared."
                }

            assertFalse(cleared.configured)
            assertNull(secretStore.readOAuthCredential(ProviderType.OpenAiCodex))
        }

    @Test
    fun `provider recovery notice is surfaced when encrypted api key cannot be restored`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.Anthropic,
                        ProviderEndpointSettings(
                            baseUrl = ProviderType.Anthropic.defaultBaseUrl,
                            modelId = "claude-sonnet-4-5",
                            timeoutSeconds = 60,
                        ),
                    ).copy(providerType = ProviderType.Anthropic),
            )
            secretStore.markRecoveryNotice(ProviderType.Anthropic)

            val viewModel = buildViewModel()
            val state =
                waitForState(viewModel) {
                    it.providerType == ProviderType.Anthropic &&
                        it.statusMessage?.contains("could not be restored", ignoreCase = true) == true
                }

            assertEquals(
                "Stored API key could not be restored on this device. Please enter it again.",
                state.statusMessage,
            )
            assertFalse(state.hasStoredApiKey)
        }

    @Test
    fun `validateConnection persists settings and reports success for configured provider`() =
        runTest {
            val viewModel = buildViewModel()

            viewModel.selectProviderType(ProviderType.OpenAiCompatible)
            waitForState(viewModel) { it.providerType == ProviderType.OpenAiCompatible }
            viewModel.onBaseUrlChanged("https://openai.example/v1")
            viewModel.onModelIdChanged("gpt-test")
            viewModel.onTimeoutChanged("30")
            viewModel.onApiKeyChanged("sk-test")
            viewModel.validateConnection()

            val state = waitForState(viewModel) { it.lastValidationSucceeded }

            assertEquals("Connection test succeeded.", state.statusMessage)
            assertEquals(ProviderType.OpenAiCompatible, settingsDataStore.settings.first().providerType)
            assertEquals("sk-test", secretStore.readApiKey(ProviderType.OpenAiCompatible))
        }

    @Test
    fun `validateConnection reports offline state before attempting a remote provider call`() =
        runTest {
            currentNetworkStatus =
                currentNetworkStatus.copy(
                    isConnected = false,
                    isValidated = false,
                )
            networkStatusFlow.value = currentNetworkStatus
            val viewModel = buildViewModel()

            viewModel.selectProviderType(ProviderType.OpenAiCompatible)
            waitForState(viewModel) { it.providerType == ProviderType.OpenAiCompatible }
            viewModel.onBaseUrlChanged("https://openai.example/v1")
            viewModel.onModelIdChanged("gpt-test")
            viewModel.onTimeoutChanged("30")
            viewModel.onApiKeyChanged("sk-test")
            viewModel.validateConnection()

            val state =
                waitForState(viewModel) {
                    !it.isValidatingConnection && it.statusMessage?.contains("No active network connection", ignoreCase = true) == true
                }

            assertFalse(state.lastValidationSucceeded)
            assertEquals(
                "No active network connection. Remote providers cannot be reached right now.",
                state.statusMessage,
            )
            assertEquals(
                "Remote provider calls will fail until network connectivity returns.",
                state.connectionHint,
            )
            assertEquals("sk-test", secretStore.readApiKey(ProviderType.OpenAiCompatible))
        }

    @Test
    fun `validateConnection allows loopback endpoint even when network is reported offline`() =
        runTest {
            currentNetworkStatus =
                currentNetworkStatus.copy(
                    isConnected = false,
                    isValidated = false,
                )
            networkStatusFlow.value = currentNetworkStatus
            val viewModel = buildViewModel()

            viewModel.selectProviderType(ProviderType.OpenAiCompatible)
            waitForState(viewModel) { it.providerType == ProviderType.OpenAiCompatible }
            viewModel.onBaseUrlChanged("http://127.0.0.1:8080/v1")
            viewModel.onModelIdChanged("gpt-test")
            viewModel.onTimeoutChanged("30")
            viewModel.onApiKeyChanged("sk-test")
            viewModel.validateConnection()

            val state = waitForState(viewModel) { it.lastValidationSucceeded }

            assertEquals("Connection test succeeded.", state.statusMessage)
            assertEquals("sk-test", secretStore.readApiKey(ProviderType.OpenAiCompatible))
        }

    @Test
    fun `validateConnection succeeds against a real mock web server`() =
        runTest {
            val server = MockWebServer()
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": "resp-settings",
                          "model": "gpt-test",
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "OK"
                              }
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )
            server.start()

            try {
                val providerRegistry =
                    buildTestProviderRegistry(
                        openAiCompatibleProvider =
                            OpenAiCompatibleProvider(
                                providerType = ProviderType.OpenAiCompatible,
                                settingsDataStore = settingsDataStore,
                                providerSecretStore = secretStore,
                                baseHttpClient = createProviderBaseHttpClient(),
                                json = json,
                            ),
                    )
                val viewModel = buildViewModel(providerRegistry)

                viewModel.selectProviderType(ProviderType.OpenAiCompatible)
                waitForState(viewModel) { it.providerType == ProviderType.OpenAiCompatible }
                viewModel.onBaseUrlChanged(server.url("/v1/").toString().removeSuffix("/"))
                viewModel.onModelIdChanged("gpt-test")
                viewModel.onTimeoutChanged("30")
                viewModel.onApiKeyChanged("sk-test")
                viewModel.validateConnection()

                val state = waitForState(viewModel) { it.lastValidationSucceeded }
                val request = server.takeRequest(5, TimeUnit.SECONDS)

                assertEquals("Connection test succeeded.", state.statusMessage)
                assertEquals("/v1/chat/completions", request!!.path)
                assertEquals("Bearer sk-test", request.getHeader("Authorization"))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `network status updates refresh settings summary and hint without clearing edits`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.Anthropic,
                        ProviderEndpointSettings(
                            baseUrl = ProviderType.Anthropic.defaultBaseUrl,
                            modelId = "claude-sonnet-4-5",
                            timeoutSeconds = 60,
                        ),
                    ).copy(providerType = ProviderType.Anthropic),
            )
            val viewModel = buildViewModel()
            waitForState(viewModel) { it.providerType == ProviderType.Anthropic && it.networkSummary == "Connected" }

            viewModel.onModelIdChanged("claude-draft")
            currentNetworkStatus =
                currentNetworkStatus.copy(
                    isConnected = false,
                    isValidated = false,
                )
            networkStatusFlow.value = currentNetworkStatus

            val offline =
                waitForState(viewModel) {
                    it.providerType == ProviderType.Anthropic &&
                        it.networkSummary == "Offline" &&
                        it.modelId == "claude-draft"
                }

            assertEquals(
                "Remote provider calls will fail until network connectivity returns.",
                offline.connectionHint,
            )

            currentNetworkStatus =
                currentNetworkStatus.copy(
                    isConnected = true,
                    isValidated = true,
                )
            networkStatusFlow.value = currentNetworkStatus

            val recovered =
                waitForState(viewModel) {
                    it.providerType == ProviderType.Anthropic &&
                        it.networkSummary == "Connected" &&
                        it.modelId == "claude-draft"
                }

            assertEquals(
                "Use Test connection to verify credentials, endpoint, and model.",
                recovered.connectionHint,
            )
        }

    @Test
    fun `selectThemePreference persists and updates state`() =
        runTest {
            val viewModel = buildViewModel()
            waitForState(viewModel) { it.activeProviderId == "fake" }

            viewModel.selectThemePreference(ThemePreference.Dark)

            val state = waitForState(viewModel) { it.themePreference == ThemePreference.Dark }

            assertEquals(ThemePreference.Dark, state.themePreference)
            assertEquals(ThemePreference.Dark, settingsDataStore.themePreference.first())
        }

    private fun buildViewModel(
        providerRegistry: ProviderRegistry = buildTestProviderRegistry(),
    ): SettingsViewModel =
        SettingsViewModel(
            providerRegistry = providerRegistry,
            settingsDataStore = settingsDataStore,
            providerSecretStore = secretStore,
            openAiCodexOAuthClient = oAuthClient,
            networkStatusProvider = networkStatusProvider,
        )

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
