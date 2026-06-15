package ai.androidclaw.data

import ai.androidclaw.testutil.MainDispatcherRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsDataStoreTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() =
        runTest {
            settingsDataStore =
                SettingsDataStore(
                    ApplicationProvider.getApplicationContext(),
                )
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        }

    @Test
    fun `save provider settings round trips per provider endpoint settings`() =
        runTest {
            val snapshot =
                ProviderSettingsSnapshot(
                    providerType = ProviderType.Gemini,
                    providerConfigs =
                        mapOf(
                            ProviderType.OpenAiCompatible to
                                ProviderEndpointSettings(
                                    baseUrl = "https://example.test/v1",
                                    modelId = "gpt-test",
                                    timeoutSeconds = 15,
                                ),
                            ProviderType.Gemini to
                                ProviderEndpointSettings(
                                    baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                                    modelId = "gemini-2.0-flash",
                                    timeoutSeconds = 45,
                                ),
                        ) +
                            ProviderType.configurableProviders
                                .filterNot { it == ProviderType.OpenAiCompatible || it == ProviderType.Gemini }
                                .associateWith { it.defaultEndpointSettings() },
                )

            settingsDataStore.saveProviderSettings(snapshot)

            assertEquals(snapshot, settingsDataStore.settings.first())
        }

    @Test
    fun `set provider type preserves stored provider endpoint details`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.OpenAiCompatible,
                        ProviderEndpointSettings(
                            baseUrl = "https://example.test/v1",
                            modelId = "gpt-test",
                            timeoutSeconds = 30,
                        ),
                    ).withEndpointSettings(
                        ProviderType.Anthropic,
                        ProviderEndpointSettings(
                            baseUrl = "https://api.anthropic.com/v1",
                            modelId = "claude-sonnet",
                            timeoutSeconds = 45,
                        ),
                    ),
            )

            settingsDataStore.setProviderType(ProviderType.Anthropic)

            val stored = settingsDataStore.settings.first()
            assertEquals(ProviderType.Anthropic, stored.providerType)
            assertEquals("https://example.test/v1", stored.endpointSettings(ProviderType.OpenAiCompatible).baseUrl)
            assertEquals("gpt-test", stored.endpointSettings(ProviderType.OpenAiCompatible).modelId)
            assertEquals("claude-sonnet", stored.endpointSettings(ProviderType.Anthropic).modelId)
        }

    @Test
    fun `provider timeout settings are normalized to safe bounds`() =
        runTest {
            val unsafeSnapshot =
                ProviderSettingsSnapshot(
                    providerConfigs =
                        mapOf(
                            ProviderType.OpenAiCompatible to
                                ProviderEndpointSettings(
                                    baseUrl = "https://example.test/v1",
                                    modelId = "gpt-test",
                                    timeoutSeconds = 0,
                                ),
                            ProviderType.Anthropic to
                                ProviderEndpointSettings(
                                    baseUrl = "https://api.anthropic.com/v1",
                                    modelId = "claude-sonnet",
                                    timeoutSeconds = MAX_PROVIDER_TIMEOUT_SECONDS + 1,
                                ),
                        ) +
                            ProviderType.configurableProviders
                                .filterNot { it == ProviderType.OpenAiCompatible || it == ProviderType.Anthropic }
                                .associateWith { it.defaultEndpointSettings() },
                )

            settingsDataStore.saveProviderSettings(unsafeSnapshot)

            val stored = settingsDataStore.settings.first()

            assertEquals(
                DEFAULT_PROVIDER_TIMEOUT_SECONDS,
                stored.endpointSettings(ProviderType.OpenAiCompatible).timeoutSeconds,
            )
            assertEquals(
                MAX_PROVIDER_TIMEOUT_SECONDS,
                stored.endpointSettings(ProviderType.Anthropic).timeoutSeconds,
            )
        }

    @Test
    fun `provider endpoint text is trimmed bounded and blank values fall back to defaults`() =
        runTest {
            val longBaseUrl = "https://example.test/" + "a".repeat(PROVIDER_BASE_URL_MAX_CHARS + 25)
            val longModelId = "model-" + "m".repeat(PROVIDER_MODEL_ID_MAX_CHARS + 25)
            val snapshot =
                ProviderSettingsSnapshot(
                    providerType = ProviderType.OpenAiCompatible,
                    providerConfigs =
                        mapOf(
                            ProviderType.OpenAiCompatible to
                                ProviderEndpointSettings(
                                    baseUrl = "  $longBaseUrl  ",
                                    modelId = "  $longModelId  ",
                                    timeoutSeconds = 60,
                                ),
                            ProviderType.DeepSeek to
                                ProviderEndpointSettings(
                                    baseUrl = "   ",
                                    modelId = "   ",
                                    timeoutSeconds = 60,
                                ),
                        ) +
                            ProviderType.configurableProviders
                                .filterNot { it == ProviderType.OpenAiCompatible || it == ProviderType.DeepSeek }
                                .associateWith { it.defaultEndpointSettings() },
                )

            settingsDataStore.saveProviderSettings(snapshot)

            val stored = settingsDataStore.settings.first()

            assertEquals(
                longBaseUrl.take(PROVIDER_BASE_URL_MAX_CHARS),
                stored.endpointSettings(ProviderType.OpenAiCompatible).baseUrl,
            )
            assertEquals(
                longModelId.take(PROVIDER_MODEL_ID_MAX_CHARS),
                stored.endpointSettings(ProviderType.OpenAiCompatible).modelId,
            )
            assertEquals(
                ProviderType.DeepSeek.defaultBaseUrl,
                stored.endpointSettings(ProviderType.DeepSeek).baseUrl,
            )
            assertEquals(
                ProviderType.DeepSeek.defaultModelId,
                stored.endpointSettings(ProviderType.DeepSeek).modelId,
            )
        }

    @Test
    fun `legacy openai storage value maps to openai compatible`() {
        assertEquals(
            ProviderType.OpenAiCompatible,
            ProviderType.fromStorage("openai"),
        )
    }

    @Test
    fun `openai codex has oauth defaults`() {
        val defaults = ProviderType.OpenAiCodex.defaultEndpointSettings()

        assertEquals("openai-codex", ProviderType.OpenAiCodex.providerId)
        assertEquals(ProviderAuthMode.OpenAiCodexDeviceCode, ProviderType.OpenAiCodex.authMode)
        assertEquals("https://chatgpt.com/backend-api/codex", defaults.baseUrl)
        assertEquals("gpt-5.4", defaults.modelId)
    }

    @Test
    fun `legacy openai codex spark default migrates to supported default model`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.OpenAiCodex,
                        ProviderEndpointSettings(
                            baseUrl = ProviderType.OpenAiCodex.defaultBaseUrl,
                            modelId = OPENAI_CODEX_LEGACY_DEFAULT_MODEL_ID,
                            timeoutSeconds = 60,
                        ),
                    ).copy(providerType = ProviderType.OpenAiCodex),
            )

            val stored = settingsDataStore.settings.first()

            assertEquals(ProviderType.OpenAiCodex, stored.providerType)
            assertEquals(OPENAI_CODEX_DEFAULT_MODEL_ID, stored.endpointSettings(ProviderType.OpenAiCodex).modelId)
        }

    @Test
    fun `blank openai codex model falls back to supported default model`() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot()
                    .withEndpointSettings(
                        ProviderType.OpenAiCodex,
                        ProviderEndpointSettings(
                            baseUrl = ProviderType.OpenAiCodex.defaultBaseUrl,
                            modelId = "  ",
                            timeoutSeconds = 60,
                        ),
                    ).copy(providerType = ProviderType.OpenAiCodex),
            )

            val stored = settingsDataStore.settings.first()

            assertEquals(OPENAI_CODEX_DEFAULT_MODEL_ID, stored.endpointSettings(ProviderType.OpenAiCodex).modelId)
        }

    @Test
    fun `deepseek has openai compatible defaults`() {
        val defaults = ProviderType.DeepSeek.defaultEndpointSettings()

        assertEquals("deepseek", ProviderType.DeepSeek.providerId)
        assertEquals(ProviderAuthMode.ApiKey, ProviderType.DeepSeek.authMode)
        assertEquals("https://api.deepseek.com", defaults.baseUrl)
        assertEquals("deepseek-v4-flash", defaults.modelId)
        assertEquals(60, defaults.timeoutSeconds)
    }

    @Test
    fun `theme preference round trips independently from provider settings`() =
        runTest {
            settingsDataStore.setThemePreference(ThemePreference.Dark)

            assertEquals(ThemePreference.Dark, settingsDataStore.themePreference.first())
            assertEquals(ProviderType.Fake, settingsDataStore.settings.first().providerType)
        }

    @Test
    fun `memory settings create stable install user id only after enabling memory`() =
        runTest {
            val initial = settingsDataStore.memorySettingsSnapshot()

            assertEquals(false, initial.enabled)

            settingsDataStore.setMemoryEnabled(false)
            val stillDisabled = settingsDataStore.memorySettingsSnapshot()

            assertEquals(false, stillDisabled.enabled)
            assertEquals(initial.installUserId, stillDisabled.installUserId)

            settingsDataStore.setMemoryEnabled(true)
            val enabled = settingsDataStore.memorySettingsSnapshot()
            settingsDataStore.setMemoryEnabled(false)
            val disabled = settingsDataStore.memorySettingsSnapshot()

            assertEquals(true, enabled.enabled)
            assertEquals(true, enabled.installUserId.isNotBlank())
            assertEquals(false, disabled.enabled)
            if (initial.installUserId.isNotBlank()) {
                assertEquals(initial.installUserId, enabled.installUserId)
            }
            assertEquals(enabled.installUserId, disabled.installUserId)
        }
}
