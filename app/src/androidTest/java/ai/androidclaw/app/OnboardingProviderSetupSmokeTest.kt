package ai.androidclaw.app

import ai.androidclaw.data.ProviderType
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingProviderSetupSmokeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var application: AndroidClawApplication

    @Before
    fun setUp() {
        application =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .applicationContext as AndroidClawApplication
        resetApplicationState(
            application = application,
            onboardingCompleted = false,
        )
    }

    @After
    fun tearDown() {
        resetApplicationState(
            application = application,
            onboardingCompleted = true,
        )
    }

    @Test
    fun onboarding_can_enter_real_provider_setup_then_return_to_fake_mode() =
        runBlocking {
            ActivityScenario.launch(MainActivity::class.java).use {
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule.onAllNodesWithTag("onboardingDialog").fetchSemanticsNodes().isNotEmpty()
                }

                composeRule.onNodeWithTag("onboardingConfigureRealProviderButton").performClick()
                composeRule
                    .onNodeWithTag("onboardingProviderChip-${ProviderType.OpenAiCompatible.storageValue}")
                    .performClick()
                composeRule
                    .onNodeWithTag("onboardingBaseUrlField")
                    .performTextReplacement("http://127.0.0.1:8080/v1")
                composeRule.onNodeWithTag("onboardingModelIdField").performTextReplacement("mock-model")
                composeRule.onNodeWithTag("onboardingTimeoutField").performTextReplacement("60")
                composeRule.onNodeWithTag("onboardingApiKeyField").performTextReplacement("test-key")
                composeRule.onNodeWithTag("onboardingBackButton").performClick()
                composeRule.waitForIdle()

                composeRule.onNodeWithTag("onboardingUseFakeButton").performClick()
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule
                        .onAllNodesWithTag("onboardingDialog")
                        .fetchSemanticsNodes()
                        .isEmpty()
                }

                val savedSettings =
                    application.container.settingsDataStore.settings
                        .first()
                assertEquals(ProviderType.Fake, savedSettings.providerType)
                assertTrue(
                    application.container.onboardingDataStore.completed
                        .first(),
                )
                assertTrue(
                    composeRule
                        .onAllNodesWithTag("onboardingDialog")
                        .fetchSemanticsNodes()
                        .isEmpty(),
                )
            }
        }
}
