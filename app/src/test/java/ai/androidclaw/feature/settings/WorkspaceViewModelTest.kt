package ai.androidclaw.feature.settings

import ai.androidclaw.R
import ai.androidclaw.data.WorkspaceStore
import ai.androidclaw.testutil.MainDispatcherRule
import ai.androidclaw.testutil.cancelAndJoinScope
import ai.androidclaw.ui.text.UiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val viewModels = mutableListOf<WorkspaceViewModel>()

    @After
    fun tearDown() =
        runBlocking {
            // Stop every ViewModel built here before Main is reset for the next test.
            viewModels.forEach { it.cancelAndJoinScope() }
        }

    private fun newStore() = WorkspaceStore(temporaryFolder.newFolder("workspace"))

    private suspend fun WorkspaceViewModel.awaitState(predicate: (WorkspaceUiState) -> Boolean): WorkspaceUiState = withTimeout(5_000) { state.first(predicate) }

    @Test
    fun `refresh lists persona files first in prompt order with their budget`() =
        runBlocking {
            val store = newStore()
            store.write("notes/ideas.md", "x")
            val viewModel = WorkspaceViewModel(store).also(viewModels::add)

            viewModel.refresh()
            val state = viewModel.awaitState { !it.loading }

            assertEquals(listOf("AGENTS.md", "SOUL.md", "USER.md", "MEMORY.md", "notes/ideas.md"), state.files.map { it.path })
            assertTrue(state.personaBytes > 0)
            assertEquals(WorkspaceStore.PROMPT_CONTEXT_MAX_BYTES, state.promptBudgetBytes)
        }

    @Test
    fun `edit save and reset round trip through the store`() =
        runBlocking {
            val store = newStore()
            val viewModel = WorkspaceViewModel(store).also(viewModels::add)

            viewModel.open("USER.md")
            viewModel.awaitState { it.editorPath == "USER.md" && !it.editorLoading }
            assertEquals(store.defaultTemplate("USER.md"), viewModel.editorText)
            assertTrue(viewModel.state.value.editorIsPersona)

            viewModel.onEditorTextChanged("Name: Lan\n")
            viewModel.save()
            val saved = viewModel.awaitState { it.message != null }
            assertEquals(UiText.res(R.string.workspace_saved, "USER.md"), saved.message)
            assertEquals("Name: Lan\n", saved.editorOriginal)
            assertEquals("Name: Lan\n", store.read("USER.md"))

            viewModel.resetToDefault()
            viewModel.awaitState { it.message == UiText.res(R.string.workspace_reset_done, "USER.md") }
            assertEquals(store.defaultTemplate("USER.md"), store.read("USER.md"))
            assertEquals(store.defaultTemplate("USER.md"), viewModel.editorText)
        }

    @Test
    fun `oversized save reports an error and keeps the file`() =
        runBlocking {
            val store = newStore()
            val viewModel = WorkspaceViewModel(store).also(viewModels::add)
            viewModel.open("MEMORY.md")
            viewModel.awaitState { it.editorPath == "MEMORY.md" && !it.editorLoading }
            val before = store.read("MEMORY.md")

            viewModel.onEditorTextChanged("a".repeat(WorkspaceStore.MAX_FILE_BYTES + 1))
            viewModel.save()
            val failed = viewModel.awaitState { it.errorMessage != null }

            assertTrue(failed.errorMessage is UiText.Res)
            assertEquals(R.string.workspace_error_save, (failed.errorMessage as UiText.Res).id)
            assertEquals(before, store.read("MEMORY.md"))
        }

    @Test
    fun `utf8 byte count matches the encoder`() {
        listOf("", "abc", "助手工作区", "emoji 😀 ok", "mixé").forEach { text ->
            assertEquals(text.toByteArray(Charsets.UTF_8).size, utf8ByteCount(text))
        }
    }
}
