package ai.androidclaw.feature.skills

import ai.androidclaw.R
import ai.androidclaw.ui.components.ClawNotice
import ai.androidclaw.ui.text.asString
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun SkillConfigurationDialog(
    state: SkillConfigurationDialogState,
    onDismiss: () -> Unit,
    onDismissMessage: () -> Unit,
    onSecretChanged: (String, String) -> Unit,
    onSecretClear: (String) -> Unit,
    onConfigChanged: (String, String) -> Unit,
    onConfigClear: (String) -> Unit,
    onSave: () -> Unit,
) {
    val editable = !state.loading && !state.saving
    AlertDialog(
        onDismissRequest = {
            if (!state.saving) {
                onDismiss()
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.skills_config_title, state.displayName))
                Text(text = state.skillKey, style = MaterialTheme.typography.labelMedium)
            }
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.loading) {
                    Text(stringResource(R.string.skills_config_loading))
                }
                state.message?.let { message ->
                    ClawNotice(text = message.asString(), onDismiss = onDismissMessage)
                }
                if (!state.loading && !state.hasEditableFields) {
                    Text(stringResource(R.string.skills_config_none))
                }
                state.secretFields.forEach { field ->
                    ConfigFieldBlock(
                        title = field.envName,
                        status =
                            when {
                                field.clearRequested -> stringResource(R.string.skills_config_will_clear)
                                field.configured -> stringResource(R.string.skills_config_secret_saved)
                                else -> stringResource(R.string.skills_config_secret_missing)
                            },
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = field.draftValue,
                            onValueChange = { value -> onSecretChanged(field.envName, value) },
                            label = { Text(stringResource(R.string.skills_config_secret_label)) },
                            enabled = editable,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        ClearButton(
                            clearRequested = field.clearRequested,
                            enabled = editable && (field.configured || field.draftValue.isNotEmpty() || field.clearRequested),
                            onClick = { onSecretClear(field.envName) },
                        )
                    }
                }
                state.configFields.forEach { field ->
                    ConfigFieldBlock(
                        title = field.path,
                        status =
                            when {
                                field.clearRequested -> stringResource(R.string.skills_config_will_clear)
                                field.storedValue != null -> stringResource(R.string.skills_config_value_saved)
                                else -> stringResource(R.string.skills_config_value_missing)
                            },
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = field.draftValue,
                            onValueChange = { value -> onConfigChanged(field.path, value) },
                            label = { Text(stringResource(R.string.skills_config_value_label)) },
                            enabled = editable,
                            singleLine = true,
                        )
                        ClearButton(
                            clearRequested = field.clearRequested,
                            enabled = editable && (field.storedValue != null || field.draftValue.isNotEmpty() || field.clearRequested),
                            onClick = { onConfigClear(field.path) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = editable && state.hasPendingChanges,
            ) {
                Text(stringResource(if (state.saving) R.string.action_saving else R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.saving) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun ConfigFieldBlock(
    title: String,
    status: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun ClearButton(
    clearRequested: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClick, enabled = enabled) {
            Text(stringResource(if (clearRequested) R.string.skills_config_undo_clear else R.string.skills_config_clear))
        }
    }
}
