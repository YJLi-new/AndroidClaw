package ai.androidclaw.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesNavigationShellAndNavigatesToTasks() {
        composeRule.onNodeWithTag("topLevelNav").fetchSemanticsNode()
        val onboardingButtons = composeRule.onAllNodesWithText("Use Fake (offline)")
        if (onboardingButtons.fetchSemanticsNodes().isNotEmpty()) {
            onboardingButtons.onFirst().performClick()
        }
        composeRule.onNodeWithTag("chatScreen").fetchSemanticsNode()
        composeRule.onNodeWithTag("chatHeading").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )

        composeRule.onNodeWithText("Tasks", useUnmergedTree = true).performClick()

        composeRule.onNodeWithTag("tasksScreen").fetchSemanticsNode()
        composeRule.onNodeWithTag("tasksHeading").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )
    }
}
