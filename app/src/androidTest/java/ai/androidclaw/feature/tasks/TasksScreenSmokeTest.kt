package ai.androidclaw.feature.tasks

import ai.androidclaw.app.AndroidClawApplication
import ai.androidclaw.app.MainActivity
import ai.androidclaw.app.resetApplicationState
import ai.androidclaw.runtime.scheduler.TaskSchedule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
    fun tasks_screen_creates_edits_and_runs_a_task() =
        runBlocking {
            ActivityScenario.launch(MainActivity::class.java).use {
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule.onAllNodesWithTag("topLevelNav").fetchSemanticsNodes().isNotEmpty()
                }

                composeRule.onNodeWithTag("navItem-tasks").performClick()
                composeRule.onNodeWithTag("createTaskFab").performClick()
                composeRule.onNodeWithTag("taskEditorScreen").fetchSemanticsNode()
                composeRule.onNodeWithTag("taskNameField").performTextInput("UI smoke task")
                composeRule.onNodeWithTag("taskPromptField").performTextInput("Say hello from a task.")
                composeRule.onNodeWithTag("taskScheduleKind-Interval").performClick()
                composeRule.onNodeWithTag("taskIntervalPreset-Hourly").performClick()
                composeRule.onNodeWithTag("saveTaskButton").performClick()

                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule.onAllNodesWithText("UI smoke task").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("tasksScreen").fetchSemanticsNode()
                composeRule.onNodeWithText("Say hello from a task.").fetchSemanticsNode()
                val created =
                    application.container.taskRepository
                        .observeTasks()
                        .first()
                        .single()
                assertTrue(created.schedule is TaskSchedule.Interval)

                composeRule.onNodeWithTag("taskCard-${created.id}").performClick()
                composeRule.onNodeWithTag("taskEditorScreen").fetchSemanticsNode()
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule.onAllNodesWithText("UI smoke task").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("taskNameField").performTextClearance()
                composeRule.onNodeWithTag("taskNameField").performTextInput("Edited smoke task")
                composeRule.onNodeWithTag("saveTaskButton").performClick()

                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule.onAllNodesWithText("Edited smoke task").fetchSemanticsNodes().isNotEmpty() &&
                        composeRule.onAllNodesWithTag("taskEditorScreen").fetchSemanticsNodes().isEmpty()
                }
                val edited = application.container.taskRepository.getTask(created.id)
                assertEquals("Edited smoke task", edited?.name)
                assertEquals(
                    1,
                    application.container.taskRepository
                        .observeTasks()
                        .first()
                        .size,
                )

                composeRule.onNodeWithTag("runTaskNow-${created.id}").performClick()
                composeRule.waitForIdle()
            }
        }
}
