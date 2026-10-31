package ai.androidclaw.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Navigation and chat smoke checks. Assertions use test tags and structure only,
 * so they hold in any locale and do not depend on FakeProvider reply text.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
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
        resetApplicationState(application = application, onboardingCompleted = false)
    }

    @After
    fun tearDown() {
        resetApplicationState(application = application, onboardingCompleted = true)
    }

    @Test
    fun onboardingDemoThenFourTabsAndDiagnostics() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForTag("onboardingDialog")
            composeRule.onNodeWithTag("onboardingUseFakeButton").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithTag("onboardingDialog").fetchSemanticsNodes().isEmpty()
            }

            composeRule.onNodeWithTag("chatScreen").fetchSemanticsNode()
            composeRule.onNodeWithTag("chatHeading").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            assertEquals(4, composeRule.onAllNodes(navItemMatcher).fetchSemanticsNodes().size)

            composeRule.onNodeWithTag("navItem-tasks").performClick()
            composeRule.onNodeWithTag("tasksHeading").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

            composeRule.onNodeWithTag("navItem-skills").performClick()
            composeRule.onNodeWithTag("skillsHeading").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

            composeRule.onNodeWithTag("navItem-settings").performClick()
            composeRule.onNodeWithTag("settingsHeading").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            composeRule.onNodeWithTag("settingsScreen").performScrollToNode(hasTestTag("settingsDiagnosticsItem"))
            composeRule.onNodeWithTag("settingsDiagnosticsItem").performClick()
            waitForTag("diagnosticsHeading")
            assertEquals(0, composeRule.onAllNodesWithTag("topLevelNav").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun chatSendShowsUserAndAssistantRows() {
        resetApplicationState(application = application, onboardingCompleted = true)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForTag("chatScreen")
            composeRule.onNodeWithTag("chatInput").performTextInput("hello from smoke test")
            composeRule.onNodeWithTag("chatSendButton").performClick()

            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithTag("chatMessage-user", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithTag("chatMessage-assistant", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithTag("chatStopButton").fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithTag("chatSendButton").fetchSemanticsNode()
        }
    }

    private val navItemMatcher =
        SemanticsMatcher("top-level nav item") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("navItem-") == true
        }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
