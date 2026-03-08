package ai.androidclaw.feature.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.androidclaw.app.AppContainer
import ai.androidclaw.runtime.skills.SkillSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SkillsUiState(
    val loading: Boolean = true,
    val skills: List<SkillSnapshot> = emptyList(),
)

class SkillsViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SkillsUiState())
    val state: StateFlow<SkillsUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            val skills = container.skillManager.refreshBundledSkills()
            mutableState.update {
                it.copy(
                    loading = false,
                    skills = skills,
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SkillsViewModel(container) as T
                }
            }
        }
    }
}

