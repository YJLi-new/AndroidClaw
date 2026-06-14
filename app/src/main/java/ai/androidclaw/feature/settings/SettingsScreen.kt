package ai.androidclaw.feature.settings

import ai.androidclaw.R
import ai.androidclaw.data.ProviderAuthMode
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.ThemePreference
import ai.androidclaw.ui.components.ClawActionPill
import ai.androidclaw.ui.components.ClawCard
import ai.androidclaw.ui.components.ClawChoicePill
import ai.androidclaw.ui.components.ClawGreen
import ai.androidclaw.ui.components.ClawGreenMuted
import ai.androidclaw.ui.components.ClawIconBadge
import ai.androidclaw.ui.components.ClawInk
import ai.androidclaw.ui.components.ClawInkMuted
import ai.androidclaw.ui.components.ClawPage
import ai.androidclaw.ui.components.ClawPrimaryButton
import ai.androidclaw.ui.components.ClawScreenHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenSetupGuide: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    ClawPage(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ClawScreenHeader(
                iconRes = R.drawable.ic_nav_settings,
                title = "Settings",
                subtitle = "Choose providers, theme preferences, and connection defaults for AndroidClaw.",
                titleTestTag = "settingsHeading",
                iconBackground = ClawGreenMuted,
            )
            AppearanceCard(
                themePreference = state.themePreference,
                onSelectTheme = viewModel::selectThemePreference,
            )
            MemoryCard(
                enabled = state.memoryEnabled,
                memoryCount = state.memoryStoredCount,
                installUserId = state.memoryInstallUserId,
                onSetEnabled = viewModel::setMemoryEnabled,
                onClearMemory = viewModel::clearMemory,
            )
            ProviderCard(
                state = state,
                onOpenSetupGuide = onOpenSetupGuide,
                onSelectProvider = viewModel::selectProviderType,
                onBaseUrlChanged = viewModel::onBaseUrlChanged,
                onModelIdChanged = viewModel::onModelIdChanged,
                onTimeoutChanged = viewModel::onTimeoutChanged,
                onApiKeyChanged = viewModel::onApiKeyChanged,
                onClearApiKey = viewModel::clearStoredApiKey,
                onStartCodexSignIn = viewModel::startOpenAiCodexDeviceCodeSignIn,
                onCancelCodexSignIn = viewModel::cancelOpenAiCodexDeviceCodeSignIn,
                onClearCodexSignIn = viewModel::clearOpenAiCodexSignIn,
                onOpenVerificationUrl = { uriHandler.openUri(it) },
                onSave = viewModel::save,
                onTestConnection = viewModel::validateConnection,
            )
            ClawCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SettingsSectionHeader(
                        iconRes = R.drawable.ic_nav_health,
                        title = "Build posture",
                    )
                    Text(
                        text = state.buildPosture,
                        color = ClawInkMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceCard(
    themePreference: ThemePreference,
    onSelectTheme: (ThemePreference) -> Unit,
) {
    ClawCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsSectionHeader(
                iconRes = R.drawable.ic_nav_settings,
                title = "Appearance",
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ThemePreference.entries.forEach { preference ->
                    ClawActionPill(
                        text = preference.displayName,
                        selected = themePreference == preference,
                        onClick = { onSelectTheme(preference) },
                        modifier = Modifier.widthIn(min = 94.dp),
                    )
                }
            }
            Text(
                text = "Current theme: ${themePreference.displayName}",
                color = ClawInkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MemoryCard(
    enabled: Boolean,
    memoryCount: Int,
    installUserId: String,
    onSetEnabled: (Boolean) -> Unit,
    onClearMemory: () -> Unit,
) {
    ClawCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsSectionHeader(
                iconRes = R.drawable.ic_nav_skills,
                title = "Memory",
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ClawActionPill(
                    text = "On",
                    selected = enabled,
                    onClick = { onSetEnabled(true) },
                    modifier = Modifier.testTag("memoryEnableButton"),
                )
                ClawActionPill(
                    text = "Off",
                    selected = !enabled,
                    onClick = { onSetEnabled(false) },
                    modifier = Modifier.testTag("memoryDisableButton"),
                )
                ClawChoicePill(
                    text = "Clear memory",
                    selected = false,
                    onClick = onClearMemory,
                    enabled = memoryCount > 0,
                    modifier = Modifier.testTag("memoryClearButton"),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsStatusLine(
                    label = "Status",
                    value = if (enabled) "Enabled" else "Disabled",
                    valueIsGood = enabled,
                    modifier = Modifier.testTag("memoryStatusText"),
                )
                SettingsStatusLine(
                    label = "Stored",
                    value = memoryCount.toString(),
                    valueIsGood = memoryCount > 0,
                    modifier = Modifier.testTag("memoryCountText"),
                )
                SettingsStatusLine(
                    label = "Scope",
                    value = if (installUserId.isBlank()) "Pending" else "Local device only",
                    valueIsGood = installUserId.isNotBlank(),
                    modifier = Modifier.testTag("memoryInstallUserIdText"),
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    state: SettingsUiState,
    onOpenSetupGuide: (() -> Unit)?,
    onSelectProvider: (ProviderType) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onModelIdChanged: (String) -> Unit,
    onTimeoutChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onStartCodexSignIn: () -> Unit,
    onCancelCodexSignIn: () -> Unit,
    onClearCodexSignIn: () -> Unit,
    onOpenVerificationUrl: (String) -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit,
) {
    ClawCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsSectionHeader(
                    iconRes = R.drawable.ic_nav_settings,
                    title = "Provider",
                    modifier = Modifier.weight(1f),
                )
                onOpenSetupGuide?.let { openSetupGuide ->
                    ClawPrimaryButton(
                        text = "Run setup guide",
                        onClick = openSetupGuide,
                        iconRes = R.drawable.ic_send_enter,
                        modifier = Modifier.widthIn(max = 190.dp),
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.availableProviders.forEach { providerType ->
                    ClawChoicePill(
                        text = providerType.displayName,
                        selected = state.providerType == providerType,
                        onClick = { onSelectProvider(providerType) },
                        modifier =
                            Modifier
                                .widthIn(min = 112.dp)
                                .testTag("providerChip-${providerType.storageValue}"),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsStatusLine(
                    label = "Active provider",
                    value = state.activeProviderId,
                    valueIsGood = true,
                    modifier = Modifier.testTag("activeProviderText"),
                )
                SettingsStatusLine(
                    label = "Network",
                    value = state.networkSummary,
                    valueIsGood = state.networkSummary.contains("connected", ignoreCase = true),
                )
                state.connectionHint?.let { hint ->
                    Text(
                        text = hint,
                        color = ClawInkMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SettingsStatusLine(
                    label = "Configured",
                    value = state.configured.toString(),
                    valueIsGood = state.configured,
                )
            }
            if (state.providerType.requiresRemoteSettings) {
                ProviderFields(
                    state = state,
                    onBaseUrlChanged = onBaseUrlChanged,
                    onModelIdChanged = onModelIdChanged,
                    onTimeoutChanged = onTimeoutChanged,
                    onApiKeyChanged = onApiKeyChanged,
                    onClearApiKey = onClearApiKey,
                    onStartCodexSignIn = onStartCodexSignIn,
                    onCancelCodexSignIn = onCancelCodexSignIn,
                    onClearCodexSignIn = onClearCodexSignIn,
                    onOpenVerificationUrl = onOpenVerificationUrl,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ClawPrimaryButton(
                    text = "Save",
                    onClick = onSave,
                    enabled = !state.isValidatingConnection,
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag("saveProviderSettingsButton"),
                )
                ClawChoicePill(
                    text = if (state.isValidatingConnection) "Testing..." else "Test connection",
                    selected = false,
                    onClick = onTestConnection,
                    enabled = !state.isValidatingConnection,
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag("testProviderConnectionButton"),
                )
            }
            state.statusMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.testTag("settingsStatusMessage"),
                    color = if (state.lastValidationSucceeded) ClawGreen else ClawInkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ProviderFields(
    state: SettingsUiState,
    onBaseUrlChanged: (String) -> Unit,
    onModelIdChanged: (String) -> Unit,
    onTimeoutChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onStartCodexSignIn: () -> Unit,
    onCancelCodexSignIn: () -> Unit,
    onClearCodexSignIn: () -> Unit,
    onOpenVerificationUrl: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = onBaseUrlChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("providerBaseUrlField"),
            label = { Text("Base URL") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.modelId,
            onValueChange = onModelIdChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("providerModelIdField"),
            label = { Text("Model ID") },
            placeholder = { Text("e.g., codex-1, codex-1-mini") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.timeoutSeconds,
            onValueChange = onTimeoutChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("providerTimeoutField"),
            label = { Text("Timeout seconds") },
            singleLine = true,
        )
        when (state.authMode) {
            ProviderAuthMode.None -> Unit
            ProviderAuthMode.ApiKey -> {
                OutlinedTextField(
                    value = state.apiKeyDraft,
                    onValueChange = onApiKeyChanged,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("providerApiKeyField"),
                    label = {
                        Text(
                            if (state.hasStoredApiKey) {
                                "API key (leave blank to keep stored key)"
                            } else {
                                "API key"
                            },
                        )
                    },
                    singleLine = true,
                )
                if (state.hasStoredApiKey) {
                    ClawChoicePill(
                        text = "Clear stored API key",
                        selected = false,
                        onClick = onClearApiKey,
                        modifier = Modifier.testTag("clearStoredApiKeyButton"),
                    )
                }
            }

            ProviderAuthMode.OpenAiCodexDeviceCode -> {
                Text(
                    text =
                        if (state.hasOAuthCredential) {
                            "Signed in: ${state.oAuthProfileLabel.orEmpty()}"
                        } else {
                            "OpenAI Codex requires device-code sign-in."
                        },
                    modifier = Modifier.testTag("openAiCodexAuthStatus"),
                    color = ClawInkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.oAuthExpiresAtText?.let { expires ->
                    Text(expires, color = ClawInkMuted, style = MaterialTheme.typography.bodyMedium)
                }
                state.deviceCodeUserCode?.let { code ->
                    Text(
                        text = "Code: $code",
                        modifier = Modifier.testTag("openAiCodexDeviceCode"),
                        color = ClawInk,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                state.deviceCodeVerificationUrl?.let { url ->
                    Text(
                        text = url,
                        modifier = Modifier.testTag("openAiCodexVerificationUrl"),
                        color = ClawInkMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ClawPrimaryButton(
                        text = if (state.isSigningInWithOpenAiCodex) "Signing in..." else "Sign in with OpenAI",
                        onClick = onStartCodexSignIn,
                        enabled = !state.isSigningInWithOpenAiCodex,
                        modifier = Modifier.testTag("openAiCodexSignInButton"),
                    )
                    state.deviceCodeVerificationUrl?.let { url ->
                        ClawChoicePill(
                            text = "Open verification page",
                            selected = false,
                            onClick = { onOpenVerificationUrl(url) },
                            modifier = Modifier.testTag("openAiCodexOpenVerificationButton"),
                        )
                    }
                    if (state.isSigningInWithOpenAiCodex) {
                        ClawChoicePill(
                            text = "Cancel",
                            selected = false,
                            onClick = onCancelCodexSignIn,
                            modifier = Modifier.testTag("openAiCodexCancelSignInButton"),
                        )
                    }
                    if (state.hasOAuthCredential) {
                        ClawChoicePill(
                            text = "Clear sign-in",
                            selected = false,
                            onClick = onClearCodexSignIn,
                            modifier = Modifier.testTag("clearOpenAiCodexSignInButton"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    iconRes: Int,
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClawIconBadge(iconRes = iconRes)
        Text(
            text = title,
            color = ClawInk,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SettingsStatusLine(
    label: String,
    value: String,
    valueIsGood: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = ClawInkMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(min = 112.dp),
        )
        Text(
            text = value,
            color = if (valueIsGood) ClawGreen else ClawInk,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
