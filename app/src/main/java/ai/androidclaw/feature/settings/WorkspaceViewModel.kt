package ai.androidclaw.feature.settings

import ai.androidclaw.R
import ai.androidclaw.app.WorkspaceDependencies
import ai.androidclaw.app.viewModelFactory
import ai.androidclaw.data.WorkspaceStore
import ai.androidclaw.ui.text.UiText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceFileUi(
    val path: String,
    val sizeBytes: Long,
    val isPersonaFile: Boolean,
)

data class WorkspaceUiState(
    val files: List<WorkspaceFileUi> = emptyList(),
    val loading: Boolean = true,
    /** Bytes of the persona files, which share the system-prompt budget. */
    val personaBytes: Long = 0,
    val promptBudgetBytes: Int = WorkspaceStore.PROMPT_CONTEXT_MAX_BYTES,
    val maxFileBytes: Int = WorkspaceStore.MAX_FILE_BYTES,
    val editorPath: String? = null,
    val editorLoading: Boolean = false,
    val editorOriginal: String = "",
    val editorIsPersona: Boolean = false,
    val saving: Boolean = false,
    val message: UiText? = null,
    val errorMessage: UiText? = null,
)

/**
 * Settings > Assistant workspace: lists the workspace files and edits one at a time.
 * The editor text lives in snapshot state so fast typing is never dropped.
 */
class WorkspaceViewModel(
    private val workspaceStore: WorkspaceStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = mutableState.asStateFlow()

    var editorText by mutableStateOf("")
        private set

    fun refresh() {
        viewModelScope.launch {
            val result = runCatchingNonCancellation { workspaceStore.list() }
            result
                .onSuccess { infos ->
                    val files =
                        infos
                            .map { WorkspaceFileUi(path = it.path, sizeBytes = it.sizeBytes, isPersonaFile = it.isPersonaFile) }
                            .sortedWith(compareBy<WorkspaceFileUi>({ !it.isPersonaFile }, { personaOrder(it.path) }, { it.path }))
                    mutableState.update { state ->
                        state.copy(
                            files = files,
                            loading = false,
                            personaBytes = files.filter(WorkspaceFileUi::isPersonaFile).sumOf(WorkspaceFileUi::sizeBytes),
                        )
                    }
                }.onFailure { error ->
                    mutableState.update {
                        it.copy(loading = false, errorMessage = UiText.res(R.string.workspace_error_load, error.messageOrDash()))
                    }
                }
        }
    }

    fun open(path: String) {
        if (mutableState.value.editorPath == path && !mutableState.value.editorLoading) return
        editorText = ""
        mutableState.update {
            it.copy(
                editorPath = path,
                editorLoading = true,
                editorOriginal = "",
                editorIsPersona = path in WorkspaceStore.PERSONA_FILE_NAMES,
                message = null,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            runCatchingNonCancellation { workspaceStore.read(path).orEmpty() }
                .onSuccess { text ->
                    if (mutableState.value.editorPath != path) return@onSuccess
                    editorText = text
                    mutableState.update { it.copy(editorLoading = false, editorOriginal = text) }
                }.onFailure { error ->
                    mutableState.update {
                        it.copy(editorLoading = false, errorMessage = UiText.res(R.string.workspace_error_load, error.messageOrDash()))
                    }
                }
        }
    }

    fun onEditorTextChanged(text: String) {
        editorText = text
    }

    fun closeEditor() {
        editorText = ""
        mutableState.update { it.copy(editorPath = null, editorOriginal = "", message = null, errorMessage = null) }
    }

    fun save() {
        val path = mutableState.value.editorPath ?: return
        val text = editorText
        mutableState.update { it.copy(saving = true, message = null, errorMessage = null) }
        viewModelScope.launch {
            runCatchingNonCancellation { workspaceStore.write(path, text) }
                .onSuccess {
                    mutableState.update {
                        it.copy(saving = false, editorOriginal = text, message = UiText.res(R.string.workspace_saved, path))
                    }
                    refresh()
                }.onFailure { error ->
                    mutableState.update {
                        it.copy(saving = false, errorMessage = UiText.res(R.string.workspace_error_save, error.messageOrDash()))
                    }
                }
        }
    }

    fun resetToDefault() {
        val path = mutableState.value.editorPath ?: return
        mutableState.update { it.copy(saving = true, message = null, errorMessage = null) }
        viewModelScope.launch {
            runCatchingNonCancellation {
                check(workspaceStore.reset(path)) { "No default for $path" }
                workspaceStore.read(path).orEmpty()
            }.onSuccess { text ->
                editorText = text
                mutableState.update {
                    it.copy(saving = false, editorOriginal = text, message = UiText.res(R.string.workspace_reset_done, path))
                }
                refresh()
            }.onFailure { error ->
                mutableState.update {
                    it.copy(saving = false, errorMessage = UiText.res(R.string.workspace_error_save, error.messageOrDash()))
                }
            }
        }
    }

    fun clearMessages() {
        mutableState.update { it.copy(message = null, errorMessage = null) }
    }

    companion object {
        fun factory(dependencies: WorkspaceDependencies) = viewModelFactory { WorkspaceViewModel(workspaceStore = dependencies.workspaceStore) }
    }
}

private fun personaOrder(path: String): Int = WorkspaceStore.PERSONA_FILE_NAMES.indexOf(path).let { if (it < 0) Int.MAX_VALUE else it }

private fun Throwable.messageOrDash(): String = message?.takeIf(String::isNotBlank)?.take(300) ?: "-"

private inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

/** UTF-8 size of [text] without allocating a byte array. */
internal fun utf8ByteCount(text: String): Int {
    var count = 0
    var index = 0
    while (index < text.length) {
        val char = text[index]
        count +=
            when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                Character.isHighSurrogate(char) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                    index++
                    4
                }
                else -> 3
            }
        index++
    }
    return count
}
