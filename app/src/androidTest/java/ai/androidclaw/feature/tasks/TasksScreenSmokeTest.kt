package ai.androidclaw.feature.tasks

import ai.androidclaw.app.AndroidClawApplication
import ai.androidclaw.app.MainActivity
import ai.androidclaw.app.resetApplicationState
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class TasksScreenSmokeTest {
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
            onboardingCompleted = true,
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
    fun tasks_screen_creates_once_task_and_shows_it_in_list() =
        runBlocking {
            ActivityScenario.launch(MainActivity::class.java).use {
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule.onAllNodesWithTag("topLevelNav").fetchSemanticsNodes().isNotEmpty()
                }

                composeRule.onNodeWithText("Tasks", useUnmergedTree = true).performClick()
                composeRule.onNodeWithTag("tasksScreen").performScrollToNode(hasTestTag("taskNameField"))
                composeRule.onNodeWithTag("taskNameField").performTextInput("UI smoke task")
                composeRule.onNodeWithTag("taskPromptField").performTextInput("Say hello from a task.")
                composeRule.onNodeWithTag("taskOnceAtField").performTextClearance()
                composeRule
                    .onNodeWithTag("taskOnceAtField")
                    .performTextInput(Instant.now().plusSeconds(900).toString())
                composeRule.onNodeWithTag("createTaskButton").performClick()

                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule.onAllNodesWithText("UI smoke task").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithText("UI smoke task").fetchSemanticsNode()
                composeRule.onNodeWithText("Say hello from a task.").fetchSemanticsNode()
                assertTrue(composeRule.onAllNodesWithText("UI smoke task").fetchSemanticsNodes().isNotEmpty())
            }
        }
}
