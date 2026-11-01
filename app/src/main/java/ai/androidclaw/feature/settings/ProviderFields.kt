package ai.androidclaw.feature.settings

import ai.androidclaw.R
import ai.androidclaw.data.ProviderAuthMode
import ai.androidclaw.data.ProviderType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Localized provider name; brand names stay as they are. */
@Composable
fun providerLabel(providerType: ProviderType): String =
    when (providerType) {
        ProviderType.Fake -> stringResource(R.string.settings_provider_fake)
        ProviderType.OpenAiCompatible -> stringResource(R.string.settings_provider_openai_compatible)
        else -> providerType.displayName
    }

data class ProviderFieldCallbacks(
    val onBaseUrlChanged: (String) -> Unit,
    val onModelIdChanged: (String) -> Unit,
    val onTimeoutChanged: (String) -> Unit,
    val onApiKeyChanged: (String) -> Unit,
    val onClearApiKey: (() -> Unit)?,
    val onStartCodexSignIn: () -> Unit,
    val onCancelCodexSignIn: () -> Unit,
    val onClearCodexSignIn: () -> Unit,
    val onOpenVerificationUrl: (String) -> Unit,
    val onAcceptsImagesChanged: ((Boolean) -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderPicker(
    providers: List<ProviderType>,
    selected: ProviderType,
    onSelect: (ProviderType) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "provider",
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = providerLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_field_provider)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .testTag("${tagPrefix}Picker"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    modifier = Modifier.testTag("${tagPrefix}Chip-${provider.storageValue}"),
                    text = { Text(providerLabel(provider)) },
                    onClick = {
                        expanded = false
                        onSelect(provider)
                    },
                )
            }
        }
    }
}

/**
 * Endpoint, model, timeout and credential fields for remote providers.
 * [fieldTagPrefix] / [codexTagPrefix] keep the test tags used by settings and onboarding.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderFields(
    state: SettingsUiState,
    callbacks: ProviderFieldCallbacks,
    fieldTagPrefix: String,
    codexTagPrefix: String,
) {
    fun codexTag(name: String): String = if (codexTagPrefix.isEmpty()) name.replaceFirstChar(Char::lowercase) else codexTagPrefix + name
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = callbacks.onBaseUrlChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("${fieldTagPrefix}BaseUrlField"),
            label = { Text(stringResource(R.string.settings_field_base_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = state.modelId,
            onValueChange = callbacks.onModelIdChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("${fieldTagPrefix}ModelIdField"),
            label = { Text(stringResource(R.string.settings_field_model)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.timeoutSeconds,
            onValueChange = callbacks.onTimeoutChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("${fieldTagPrefix}TimeoutField"),
            label = { Text(stringResource(R.string.settings_field_timeout)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        val onAcceptsImagesChanged = callbacks.onAcceptsImagesChanged
        if (onAcceptsImagesChanged != null && !state.providerType.defaultSupportsImages) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.acceptsImages,
                            role = Role.Switch,
                            onValueChange = onAcceptsImagesChanged,
                        ).testTag("${fieldTagPrefix}AcceptsImagesSwitch"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_field_accepts_images), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_field_accepts_images_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.acceptsImages, onCheckedChange = null)
            }
        }
        when (state.authMode) {
            ProviderAuthMode.None -> Unit
            ProviderAuthMode.ApiKey -> {
                OutlinedTextField(
                    value = state.apiKeyDraft,
                    onValueChange = callbacks.onApiKeyChanged,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("${fieldTagPrefix}ApiKeyField"),
                    label = { Text(stringResource(R.string.settings_field_api_key)) },
                    supportingText =
                        if (state.hasStoredApiKey) {
                            { Text(stringResource(R.string.settings_field_api_key_stored)) }
                        } else {
                            null
                        },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                val onClear = callbacks.onClearApiKey
                if (state.hasStoredApiKey && onClear != null) {
                    OutlinedButton(
                        onClick = onClear,
                        modifier = Modifier.testTag("clearStoredApiKeyButton"),
                    ) {
                        Text(stringResource(R.string.settings_action_clear_api_key))
                    }
                }
            }

            ProviderAuthMode.OpenAiCodexDeviceCode -> {
                Text(
                    text =
                        if (state.hasOAuthCredential) {
                            stringResource(
                                R.string.settings_codex_signed_in,
                                state.oAuthProfileLabel ?: stringResource(R.string.settings_codex_account_fallback),
                            )
                        } else {
                            stringResource(R.string.settings_codex_required)
                        },
                    modifier = Modifier.testTag(codexTag("OpenAiCodexAuthStatus")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.oAuthExpiresAt?.let { expires ->
                    val formatter =
                        ai.androidclaw.ui.format
                            .rememberDateTimeFormatter()
                    Text(
                        text = stringResource(R.string.settings_codex_expires, formatter.format(expires)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.deviceCodeUserCode?.let { code ->
                    Text(
                        text = stringResource(R.string.settings_codex_code, code),
                        modifier = Modifier.testTag(codexTag("OpenAiCodexDeviceCode")),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = callbacks.onStartCodexSignIn,
                        enabled = !state.isSigningInWithOpenAiCodex,
                        modifier = Modifier.testTag(codexTag("OpenAiCodexSignInButton")),
                    ) {
                        Text(
                            stringResource(
                                if (state.isSigningInWithOpenAiCodex) R.string.settings_codex_signing_in else R.string.settings_codex_sign_in,
                            ),
                        )
                    }
                    state.deviceCodeVerificationUrl?.let { url ->
                        OutlinedButton(
                            onClick = { callbacks.onOpenVerificationUrl(url) },
                            modifier = Modifier.testTag(codexTag("OpenAiCodexOpenVerificationButton")),
                        ) {
                            Text(stringResource(R.string.settings_codex_open_page))
                        }
                    }
                    if (state.isSigningInWithOpenAiCodex) {
                        OutlinedButton(
                            onClick = callbacks.onCancelCodexSignIn,
                            modifier = Modifier.testTag(codexTag("OpenAiCodexCancelSignInButton")),
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                    if (state.hasOAuthCredential) {
                        OutlinedButton(
                            onClick = callbacks.onClearCodexSignIn,
                            modifier = Modifier.testTag("clear${codexTag("OpenAiCodexSignInButton").replaceFirstChar(Char::uppercase)}"),
                        ) {
                            Text(stringResource(R.string.settings_codex_clear))
                        }
                    }
                }
            }
        }
    }
}
