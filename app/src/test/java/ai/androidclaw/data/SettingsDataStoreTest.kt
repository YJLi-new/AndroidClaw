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
    fun setUp() = runTest {
        settingsDataStore = SettingsDataStore(
            ApplicationProvider.getApplicationContext(),
        )
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
    }

    @After
    fun tearDown() = runTest {
        settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
    }

    @Test
    fun `save provider settings round trips typed settings`() = runTest {
        val snapshot = ProviderSettingsSnapshot(
            providerType = ProviderType.OpenAiCompatible,
            openAiBaseUrl = "https://example.test/v1",
            openAiModelId = "gpt-test",
            openAiTimeoutSeconds = 15,
        )

        settingsDataStore.saveProviderSettings(snapshot)

        assertEquals(snapshot, settingsDataStore.settings.first())
    }

    @Test
    fun `set provider type preserves stored openai details`() = runTest {
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot(
                providerType = ProviderType.Fake,
                openAiBaseUrl = "https://example.test/v1",
                openAiModelId = "gpt-test",
                openAiTimeoutSeconds = 30,
            ),
        )

        settingsDataStore.setProviderType(ProviderType.OpenAiCompatible)

        assertEquals(
            ProviderSettingsSnapshot(
                providerType = ProviderType.OpenAiCompatible,
                openAiBaseUrl = "https://example.test/v1",
                openAiModelId = "gpt-test",
                openAiTimeoutSeconds = 30,
            ),
            settingsDataStore.settings.first(),
        )
    }

    @Test
    fun `legacy openai storage value maps to openai compatible`() = runTest {
        settingsDataStore.saveProviderSettings(
            ProviderSettingsSnapshot(
                providerType = ProviderType.OpenAiCompatible,
                openAiModelId = "gpt-test",
            ),
        )

        settingsDataStore.setProviderType(ProviderType.Fake)

        assertEquals(
            ProviderType.OpenAiCompatible,
            ProviderType.fromStorage("openai"),
        )
    }
}
