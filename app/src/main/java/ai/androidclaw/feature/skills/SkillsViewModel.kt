package ai.androidclaw.feature.skills

import android.net.Uri
import ai.androidclaw.app.SkillsDependencies
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillSnapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SkillsUiState(
    val loading: Boolean = true,
    val isImporting: Boolean = false,
    val skills: List<SkillSnapshot> = emptyList(),
    val statusMessage: String? = null,
)

class SkillsViewModel(
    private val skillManager: SkillManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SkillsUiState())
    val state: StateFlow<SkillsUiState> = mutableState.asStateFlow()

    init {
        loadSkills(forceRefresh = false)
    }

    fun refresh() {
        loadSkills(forceRefresh = true)
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        viewModelScope.launch {
            skillManager.setEnabled(skillId = skillId, enabled = enabled)
            mutableState.update {
                it.copy(
                    statusMessage = if (enabled) {
                        "Enabled skill."
                    } else {
                        "Disabled skill."
                    },
                )
            }
            loadSkills(forceRefresh = true)
        }
    }

    fun importLocalSkills(uri: Uri) {
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isImporting = true,
                    statusMessage = null,
                )
            }
            runCatching {
                skillManager.importLocalSkills(uri)
            }.onSuccess { result ->
                mutableState.update {
                    it.copy(
                        isImporting = false,
                        statusMessage = buildString {
                            append("Imported ${result.importedSkillNames.size} skill")
                            if (result.importedSkillNames.size != 1) {
                                append('s')
                            }
                            if (result.importedSkillNames.isNotEmpty()) {
                                append(": ")
                                append(result.importedSkillNames.joinToString())
                            }
                            if (result.replacedSkillNames.isNotEmpty()) {
                                append(". Replaced: ")
                                append(result.replacedSkillNames.joinToString())
                            }
                        },
                    )
                }
                loadSkills(forceRefresh = true)
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        isImporting = false,
                        statusMessage = error.message ?: "Skill import failed.",
                    )
                }
            }
        }
    }

    fun clearStatusMessage() {
        mutableState.update { it.copy(statusMessage = null) }
    }

    private fun loadSkills(forceRefresh: Boolean) {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            runCatching {
                skillManager.refreshSkillInventory(forceRefresh = forceRefresh)
            }.onSuccess { skills ->
                mutableState.update {
                    it.copy(
                        loading = false,
                        skills = skills,
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        loading = false,
                        statusMessage = error.message ?: "Failed to load skills.",
                    )
                }
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
                    ) as T
                }
            }
        }
    }
}
