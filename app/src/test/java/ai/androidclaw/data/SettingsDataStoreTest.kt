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
    fun `legacy openai storage value maps to openai compatible`() {
        assertEquals(
            ProviderType.OpenAiCompatible,
            ProviderType.fromStorage("openai"),
        )
    }

    @Test
    fun `theme preference round trips independently from provider settings`() =
        runTest {
            settingsDataStore.setThemePreference(ThemePreference.Dark)

            assertEquals(ThemePreference.Dark, settingsDataStore.themePreference.first())
            assertEquals(ProviderType.Fake, settingsDataStore.settings.first().providerType)
        }
}
