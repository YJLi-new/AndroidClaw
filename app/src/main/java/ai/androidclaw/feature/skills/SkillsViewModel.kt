package ai.androidclaw.feature.skills

import ai.androidclaw.app.SkillsDependencies
import ai.androidclaw.app.viewModelFactory
import ai.androidclaw.data.SKILL_CONFIG_VALUE_MAX_CHARS
import ai.androidclaw.data.SKILL_SECRET_VALUE_MAX_CHARS
import ai.androidclaw.runtime.skills.SkillConfigField
import ai.androidclaw.runtime.skills.SkillImportResult
import ai.androidclaw.runtime.skills.SkillManager
import ai.androidclaw.runtime.skills.SkillSecretField
import ai.androidclaw.runtime.skills.SkillSnapshot
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val SKILL_CONFIGURATION_INPUT_TRUNCATED_NOTICE =
    "Skill configuration input truncated by AndroidClaw to keep the dialog responsive."
internal const val SKILL_IMPORT_STATUS_MAX_LISTED_NAMES = 4
internal const val SKILL_IMPORT_STATUS_NAME_MAX_CHARS = 80
internal const val SKILL_IMPORT_STATUS_MAX_CHARS = 1_000
internal const val SKILL_UI_MESSAGE_MAX_CHARS = 1_000

private data class BoundedSkillConfigurationInput(
    val value: String,
    val wasTruncated: Boolean,
)

private fun String.toBoundedSkillConfigurationInput(maxChars: Int): BoundedSkillConfigurationInput {
    require(maxChars > 0) { "Skill configuration input max must be positive." }
    val boundedValue = take(maxChars)
    return BoundedSkillConfigurationInput(
        value = boundedValue,
        wasTruncated = length > boundedValue.length,
    )
}

private fun skillConfigurationInputMessage(wasTruncated: Boolean): String? =
    if (wasTruncated) {
        SKILL_CONFIGURATION_INPUT_TRUNCATED_NOTICE
    } else {
        null
    }

data class SkillsUiState(
    val loading: Boolean = true,
    val isImporting: Boolean = false,
    val skills: List<SkillSnapshot> = emptyList(),
    val statusMessage: String? = null,
    val configurationDialog: SkillConfigurationDialogState? = null,
)

data class SkillConfigurationDialogState(
    val skillId: String,
    val skillKey: String,
    val displayName: String,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
    val secretFields: List<EditableSkillSecretField> = emptyList(),
    val configFields: List<EditableSkillConfigField> = emptyList(),
) {
    val hasEditableFields: Boolean
        get() = secretFields.isNotEmpty() || configFields.isNotEmpty()

    val hasPendingChanges: Boolean
        get() = secretFields.any { it.hasPendingChange } || configFields.any { it.hasPendingChange }
}

data class EditableSkillSecretField(
    val envName: String,
    val configured: Boolean,
    val draftValue: String = "",
    val clearRequested: Boolean = false,
) {
    val hasPendingChange: Boolean
        get() = draftValue.isNotBlank() || clearRequested
}

data class EditableSkillConfigField(
    val path: String,
    val storedValue: String?,
    val draftValue: String,
    val clearRequested: Boolean = false,
) {
    val hasPendingChange: Boolean
        get() = clearRequested || draftValue != (storedValue ?: "")
}

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

    fun openConfiguration(skill: SkillSnapshot) {
        mutableState.update {
            it.copy(
                configurationDialog =
                    SkillConfigurationDialogState(
                        skillId = skill.id,
                        skillKey = skill.skillKey,
                        displayName = skill.displayName,
                    ),
            )
        }
        viewModelScope.launch {
            runCatching {
                skillManager.readConfiguration(skill)
            }.onSuccess { configuration ->
                updateDialog { dialog ->
                    dialog.copy(
                        loading = false,
                        message = configuration.recoveryMessage.toBoundedSkillUiMessageOrNull(),
                        secretFields = configuration.secretFields.toEditableSecretFields(),
                        configFields = configuration.configFields.toEditableConfigFields(),
                    )
                }
            }.onFailure { error ->
                updateDialog { dialog ->
                    dialog.copy(
                        loading = false,
                        message = boundedSkillUiMessage(error.message, "Failed to load skill configuration."),
                    )
                }
            }
        }
    }

    fun dismissConfiguration() {
        mutableState.update { it.copy(configurationDialog = null) }
    }

    fun clearConfigurationMessage() {
        updateDialog { dialog -> dialog.copy(message = null) }
    }

    fun updateSecretDraft(
        envName: String,
        value: String,
    ) {
        val boundedInput = value.toBoundedSkillConfigurationInput(SKILL_SECRET_VALUE_MAX_CHARS)
        updateDialog { dialog ->
            dialog.copy(
                message = skillConfigurationInputMessage(boundedInput.wasTruncated),
                secretFields =
                    dialog.secretFields.map { field ->
                        if (field.envName == envName) {
                            field.copy(
                                draftValue = boundedInput.value,
                                clearRequested = false,
                            )
                        } else {
                            field
                        }
                    },
            )
        }
    }

    fun requestSecretClear(envName: String) {
        updateDialog { dialog ->
            dialog.copy(
                message = null,
                secretFields =
                    dialog.secretFields.map { field ->
                        if (field.envName == envName) {
                            field.copy(
                                draftValue = "",
                                clearRequested = !field.clearRequested,
                            )
                        } else {
                            field
                        }
                    },
            )
        }
    }

    fun updateConfigDraft(
        path: String,
        value: String,
    ) {
        val boundedInput = value.toBoundedSkillConfigurationInput(SKILL_CONFIG_VALUE_MAX_CHARS)
        updateDialog { dialog ->
            dialog.copy(
                message = skillConfigurationInputMessage(boundedInput.wasTruncated),
                configFields =
                    dialog.configFields.map { field ->
                        if (field.path == path) {
                            field.copy(
                                draftValue = boundedInput.value,
                                clearRequested = false,
                            )
                        } else {
                            field
                        }
                    },
            )
        }
    }

    fun requestConfigClear(path: String) {
        updateDialog { dialog ->
            dialog.copy(
                message = null,
                configFields =
                    dialog.configFields.map { field ->
                        if (field.path == path) {
                            field.copy(
                                draftValue =
                                    if (field.clearRequested) {
                                        field.storedValue.orEmpty()
                                    } else {
                                        ""
                                    },
                                clearRequested = !field.clearRequested,
                            )
                        } else {
                            field
                        }
                    },
            )
        }
    }

    fun saveConfiguration() {
        val dialog = mutableState.value.configurationDialog ?: return
        if (dialog.loading || dialog.saving || !dialog.hasPendingChanges) {
            return
        }
        viewModelScope.launch {
            updateDialog { current ->
                current.copy(
                    saving = true,
                    message = null,
                )
            }

            val currentDialog = mutableState.value.configurationDialog ?: return@launch
            val secretUpdates =
                currentDialog.secretFields
                    .filter { !it.clearRequested && it.draftValue.isNotBlank() }
                    .associate { it.envName to it.draftValue }
            val clearedSecrets =
                currentDialog.secretFields
                    .filter { it.clearRequested }
                    .mapTo(linkedSetOf()) { it.envName }
            val configUpdates =
                currentDialog.configFields
                    .filter { it.clearRequested || it.draftValue != (it.storedValue ?: "") }
                    .associate { field ->
                        field.path to if (field.clearRequested) null else field.draftValue
                    }

            runCatching {
                skillManager.saveConfiguration(
                    skillKey = currentDialog.skillKey,
                    secretUpdates = secretUpdates,
                    clearedSecrets = clearedSecrets,
                    configUpdates = configUpdates,
                )
                val refreshedSkills = skillManager.refreshSkillInventory(forceRefresh = true)
                val refreshedSkill =
                    refreshedSkills.firstOrNull { it.id == currentDialog.skillId }
                        ?: refreshedSkills.firstOrNull { it.skillKey == currentDialog.skillKey }
                val refreshedConfiguration = refreshedSkill?.let { skillManager.readConfiguration(it) }
                refreshedSkills to refreshedConfiguration
            }.onSuccess { (skills, configuration) ->
                mutableState.update { state ->
                    val existingDialog = state.configurationDialog ?: return@update state
                    state.copy(
                        skills = skills,
                        statusMessage = boundedSkillUiMessage("Saved configuration for ${existingDialog.displayName}.", "Saved configuration."),
                        configurationDialog =
                            existingDialog.copy(
                                loading = false,
                                saving = false,
                                message = if (configuration == null) "Saved configuration." else null,
                                secretFields =
                                    configuration?.secretFields?.toEditableSecretFields()
                                        ?: existingDialog.secretFields.map { field ->
                                            field.copy(
                                                configured = !field.clearRequested,
                                                draftValue = "",
                                                clearRequested = false,
                                            )
                                        },
                                configFields =
                                    configuration?.configFields?.toEditableConfigFields()
                                        ?: existingDialog.configFields.map { field ->
                                            field.copy(
                                                storedValue = if (field.clearRequested) null else field.draftValue,
                                                draftValue = if (field.clearRequested) "" else field.draftValue,
                                                clearRequested = false,
                                            )
                                        },
                            ),
                    )
                }
            }.onFailure { error ->
                updateDialog { existingDialog ->
                    existingDialog.copy(
                        saving = false,
                        message = boundedSkillUiMessage(error.message, "Failed to save skill configuration."),
                    )
                }
            }
        }
    }

    fun toggleSkill(
        skillId: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            skillManager.setEnabled(skillId = skillId, enabled = enabled)
            mutableState.update {
                it.copy(
                    statusMessage =
                        if (enabled) {
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
                        statusMessage = buildSkillImportStatusMessage(result),
                    )
                }
                loadSkills(forceRefresh = true)
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        isImporting = false,
                        statusMessage = boundedSkillUiMessage(error.message, "Skill import failed."),
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
                        statusMessage = boundedSkillUiMessage(error.message, "Failed to load skills."),
                    )
                }
            }
        }
    }

    private fun updateDialog(
        transform: (SkillConfigurationDialogState) -> SkillConfigurationDialogState,
    ) {
        mutableState.update { state ->
            val dialog = state.configurationDialog ?: return@update state
            state.copy(configurationDialog = transform(dialog))
        }
    }

    companion object {
        fun factory(dependencies: SkillsDependencies) =
            viewModelFactory {
                SkillsViewModel(
                    skillManager = dependencies.skillManager,
                )
            }
    }
}

internal fun boundedSkillUiMessage(
    message: String?,
    fallback: String,
): String = (message?.takeIf(String::isNotBlank) ?: fallback).take(SKILL_UI_MESSAGE_MAX_CHARS)

private fun String?.toBoundedSkillUiMessageOrNull(): String? =
    this
        ?.take(SKILL_UI_MESSAGE_MAX_CHARS)
        ?.takeIf(String::isNotBlank)

internal fun buildSkillImportStatusMessage(result: SkillImportResult): String =
    buildString {
        append("Imported ${result.importedSkillNames.size} skill")
        if (result.importedSkillNames.size != 1) {
            append('s')
        }
        appendSkillNameSummary(prefix = ": ", names = result.importedSkillNames)
        appendSkillNameSummary(prefix = ". Replaced: ", names = result.replacedSkillNames)
    }.take(SKILL_IMPORT_STATUS_MAX_CHARS)

private fun StringBuilder.appendSkillNameSummary(
    prefix: String,
    names: List<String>,
) {
    if (names.isEmpty()) {
        return
    }
    append(prefix)
    append(
        names
            .take(SKILL_IMPORT_STATUS_MAX_LISTED_NAMES)
            .joinToString { name -> name.take(SKILL_IMPORT_STATUS_NAME_MAX_CHARS) },
    )
    val omittedCount = names.size - SKILL_IMPORT_STATUS_MAX_LISTED_NAMES
    if (omittedCount > 0) {
        append(", +")
        append(omittedCount)
        append(" more")
    }
}

private fun List<SkillSecretField>.toEditableSecretFields(): List<EditableSkillSecretField> =
    map { field ->
        EditableSkillSecretField(
            envName = field.envName,
            configured = field.configured,
        )
    }

private fun List<SkillConfigField>.toEditableConfigFields(): List<EditableSkillConfigField> =
    map { field ->
        EditableSkillConfigField(
            path = field.path,
            storedValue = field.value,
            draftValue = field.value.orEmpty(),
        )
    }
