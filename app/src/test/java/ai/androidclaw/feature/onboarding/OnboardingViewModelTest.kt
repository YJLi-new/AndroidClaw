package ai.androidclaw.feature.onboarding

import ai.androidclaw.data.OnboardingDataStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.testutil.MainDispatcherRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var onboardingDataStore: OnboardingDataStore
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() =
        runTest {
            val application = ApplicationProvider.getApplicationContext<android.app.Application>()
            onboardingDataStore = OnboardingDataStore(application)
            settingsDataStore = SettingsDataStore(application)
            onboardingDataStore.setCompleted(false)
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        }

    @After
    fun tearDown() =
        runTest {
            onboardingDataStore.setCompleted(false)
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        }

    @Test
    fun firstRun_withFakeProvider_showsWelcome() =
        runTest {
            val viewModel = buildViewModel()

            val state = waitForState(viewModel) { it.visible }

            assertTrue(state.visible)
            assertEquals(OnboardingStep.Welcome, state.step)
        }

    @Test
    fun realProviderSelection_skipsAutomaticOnboarding() =
        runTest {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot(providerType = ProviderType.OpenAiCompatible),
            )

            val viewModel = buildViewModel()
            val state = waitForState(viewModel) { !it.visible }

            assertFalse(state.visible)
        }

    @Test
    fun useFakeMode_marksOnboardingComplete() =
        runTest {
            val viewModel = buildViewModel()
            waitForState(viewModel) { it.visible }

            viewModel.useFakeMode()

            val state = waitForState(viewModel) { !it.visible }

            assertFalse(state.visible)
            assertTrue(onboardingDataStore.completed.first())
            assertEquals(ProviderType.Fake, settingsDataStore.settings.first().providerType)
        }

    private fun buildViewModel(): OnboardingViewModel =
        OnboardingViewModel(
            onboardingDataStore = onboardingDataStore,
            settingsDataStore = settingsDataStore,
        )

    private fun TestScope.waitForState(
        viewModel: OnboardingViewModel,
        predicate: (OnboardingUiState) -> Boolean,
    ): OnboardingUiState {
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
