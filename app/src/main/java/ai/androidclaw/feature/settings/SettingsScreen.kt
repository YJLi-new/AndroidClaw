package ai.androidclaw.feature.settings

import ai.androidclaw.BuildConfig
import ai.androidclaw.R
import ai.androidclaw.data.ThemePreference
import ai.androidclaw.ui.components.ClawCard
import ai.androidclaw.ui.components.ClawConfirmDialog
import ai.androidclaw.ui.components.ClawIcon
import ai.androidclaw.ui.components.ClawListItem
import ai.androidclaw.ui.components.ClawNotice
import ai.androidclaw.ui.components.ClawSectionHeader
import ai.androidclaw.ui.components.ClawStatusBadge
import ai.androidclaw.ui.components.ClawTopBar
import ai.androidclaw.ui.components.NoticeTone
import ai.androidclaw.ui.locale.AppLanguage
import ai.androidclaw.ui.locale.AppLocaleController
import ai.androidclaw.ui.text.asString
import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenSetupGuide: (() -> Unit)? = null,
    onOpenDiagnostics: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmClearMemory by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.testTag("settingsScreen"),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            ClawTopBar(
                title = stringResource(R.string.settings_title),
                titleTestTag = "settingsHeading",
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            ClawSectionHeader(stringResource(R.string.settings_section_provider))
            ProviderSection(state = state, viewModel = viewModel, onOpenSetupGuide = onOpenSetupGuide)

            ClawSectionHeader(stringResource(R.string.settings_section_memory))
            MemorySection(
                state = state,
                onSetEnabled = viewModel::setMemoryEnabled,
                onClear = { confirmClearMemory = true },
            )

            ClawSectionHeader(stringResource(R.string.settings_section_appearance))
            ThemeSelector(selected = state.themePreference, onSelect = viewModel::selectThemePreference)

            ClawSectionHeader(stringResource(R.string.settings_section_language))
            LanguageSelector()

            ClawSectionHeader(stringResource(R.string.settings_section_advanced))
            ClawListItem(
                title = stringResource(R.string.settings_diagnostics),
                supporting = stringResource(R.string.settings_diagnostics_summary),
                leadingIcon = R.drawable.ic_bug,
                onClick = onOpenDiagnostics,
                modifier = Modifier.testTag("settingsDiagnosticsItem"),
                trailing = { ClawIcon(iconRes = R.drawable.ic_chevron_right, contentDescription = null) },
            )

            ClawSectionHeader(stringResource(R.string.settings_section_about))
            ClawListItem(
                title = stringResource(R.string.app_name),
                supporting = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE),
                leadingIcon = R.drawable.ic_info,
                modifier = Modifier.testTag("settingsAboutItem"),
            )
            Text(
                text = stringResource(R.string.settings_about_body),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmClearMemory) {
        ClawConfirmDialog(
            title = stringResource(R.string.settings_memory_clear_title),
            text = pluralStringResource(R.plurals.settings_memory_clear_body, state.memoryStoredCount, state.memoryStoredCount),
            confirmLabel = stringResource(R.string.settings_memory_clear),
            destructive = true,
            onConfirm = {
                confirmClearMemory = false
                viewModel.clearMemory()
            },
            onDismiss = { confirmClearMemory = false },
        )
    }
}

@Composable
private fun ProviderSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenSetupGuide: (() -> Unit)?,
) {
    val uriHandler = LocalUriHandler.current
    ClawCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderPicker(
                providers = state.availableProviders,
                selected = state.providerType,
                onSelect = viewModel::selectProviderType,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ClawStatusBadge(
                    text =
                        stringResource(
                            if (state.configured) R.string.settings_status_configured else R.string.settings_status_not_configured,
                        ),
                    tone = if (state.configured) NoticeTone.Success else NoticeTone.Warning,
                    modifier = Modifier.testTag("providerConfiguredBadge"),
                )
                if (state.providerType.requiresRemoteSettings) {
                    ClawStatusBadge(
                        text = networkLabel(state),
                        tone = if (state.networkConnected) NoticeTone.Info else NoticeTone.Error,
                    )
                }
                Text(
                    text = state.activeProviderId,
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag("activeProviderText"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            state.connectionHint?.let { hint ->
                Text(
                    text = hint.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.providerType.requiresRemoteSettings) {
                ProviderFields(
                    state = state,
                    callbacks =
                        ProviderFieldCallbacks(
                            onBaseUrlChanged = viewModel::onBaseUrlChanged,
                            onModelIdChanged = viewModel::onModelIdChanged,
                            onTimeoutChanged = viewModel::onTimeoutChanged,
                            onApiKeyChanged = viewModel::onApiKeyChanged,
                            onClearApiKey = viewModel::clearStoredApiKey,
                            onStartCodexSignIn = viewModel::startOpenAiCodexDeviceCodeSignIn,
                            onCancelCodexSignIn = viewModel::cancelOpenAiCodexDeviceCodeSignIn,
                            onClearCodexSignIn = viewModel::clearOpenAiCodexSignIn,
                            onOpenVerificationUrl = { url -> runCatching { uriHandler.openUri(url) } },
                        ),
                    fieldTagPrefix = "provider",
                    codexTagPrefix = "",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::save,
                    enabled = !state.isValidatingConnection,
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag("saveProviderSettingsButton"),
                ) {
                    Text(stringResource(R.string.action_save))
                }
                OutlinedButton(
                    onClick = viewModel::validateConnection,
                    enabled = !state.isValidatingConnection,
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag("testProviderConnectionButton"),
                ) {
                    if (state.isValidatingConnection) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).padding(2.dp), strokeWidth = 2.dp)
                    }
                    Text(stringResource(if (state.isValidatingConnection) R.string.settings_testing else R.string.settings_test))
                }
            }
            state.statusMessage?.let { message ->
                ClawNotice(
                    text = message.asString(),
                    modifier = Modifier.testTag("settingsStatusMessage"),
                    tone = if (state.lastValidationSucceeded) NoticeTone.Success else NoticeTone.Info,
                )
            }
        }
    }
    onOpenSetupGuide?.let { openGuide ->
        ClawListItem(
            title = stringResource(R.string.settings_setup_guide),
            supporting = stringResource(R.string.settings_setup_guide_summary),
            leadingIcon = R.drawable.ic_cloud,
            onClick = openGuide,
            modifier = Modifier.testTag("settingsSetupGuideItem"),
        )
    }
}

@Composable
private fun networkLabel(state: SettingsUiState): String =
    stringResource(
        when {
            !state.networkConnected -> R.string.settings_network_offline
            !state.networkValidated -> R.string.settings_network_unvalidated
            state.networkMetered -> R.string.settings_network_metered
            else -> R.string.settings_network_online
        },
    )

@Composable
private fun MemorySection(
    state: SettingsUiState,
    onSetEnabled: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    ClawListItem(
        title = stringResource(R.string.settings_memory_toggle),
        supporting = stringResource(R.string.settings_memory_help),
        leadingIcon = R.drawable.ic_lightbulb,
        onClick = { onSetEnabled(!state.memoryEnabled) },
        modifier = Modifier.testTag("memoryToggleItem"),
        trailing = {
            Switch(
                checked = state.memoryEnabled,
                onCheckedChange = onSetEnabled,
                modifier = Modifier.testTag("memoryEnabledSwitch"),
            )
        },
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = pluralStringResource(R.plurals.settings_memory_count, state.memoryStoredCount, state.memoryStoredCount),
            modifier =
                Modifier
                    .weight(1f)
                    .testTag("memoryCountText"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onClear,
            enabled = state.memoryStoredCount > 0,
            modifier = Modifier.testTag("memoryClearButton"),
        ) {
            Text(stringResource(R.string.settings_memory_clear))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(
    selected: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
) {
    val options = ThemePreference.entries
    SingleChoiceSegmentedButtonRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        options.forEachIndexed { index, preference ->
            SegmentedButton(
                selected = selected == preference,
                onClick = { onSelect(preference) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.testTag("themeOption-${preference.storageValue}"),
            ) {
                Text(
                    stringResource(
                        when (preference) {
                            ThemePreference.System -> R.string.settings_theme_system
                            ThemePreference.Light -> R.string.settings_theme_light
                            ThemePreference.Dark -> R.string.settings_theme_dark
                        },
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector() {
    val context = LocalContext.current
    val current = remember(context) { AppLocaleController.current(context) }
    val options = AppLanguage.entries
    SingleChoiceSegmentedButtonRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        options.forEachIndexed { index, language ->
            SegmentedButton(
                selected = current == language,
                onClick = {
                    (context as? Activity)?.let { activity -> AppLocaleController.apply(activity, language) }
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.testTag("languageOption-${language.name}"),
            ) {
                Text(
                    stringResource(
                        when (language) {
                            AppLanguage.System -> R.string.settings_language_system
                            AppLanguage.English -> R.string.settings_language_english
                            AppLanguage.SimplifiedChinese -> R.string.settings_language_chinese
                        },
                    ),
                    maxLines = 1,
                )
            }
        }
    }
    Text(
        text = stringResource(R.string.settings_language_help),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
