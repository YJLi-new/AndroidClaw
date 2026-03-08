package ai.androidclaw.feature.skills

import ai.androidclaw.app.SkillsDependencies
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.runtime.skills.SkillManager
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
    private val skillManager: SkillManager,
    private val skillRepository: SkillRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SkillsUiState())
    val state: StateFlow<SkillsUiState> = mutableState.asStateFlow()

    init {
        loadSkills(forceRefresh = false)
    }

    fun refresh() {
        loadSkills(forceRefresh = true)
    }

    private fun loadSkills(forceRefresh: Boolean) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            val skills = skillManager.refreshBundledSkills(forceRefresh = forceRefresh)
            mutableState.update {
                it.copy(
                    loading = false,
                    skills = skills,
                )
            }
        }
    }

    companion object {
        fun factory(dependencies: SkillsDependencies): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SkillsViewModel(
                        skillManager = dependencies.skillManager,
                        skillRepository = dependencies.skillRepository,
                    ) as T
                }
            }
        }
    }
}
