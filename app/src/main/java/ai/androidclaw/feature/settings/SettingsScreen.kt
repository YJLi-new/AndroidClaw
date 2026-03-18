package ai.androidclaw.feature.settings

import ai.androidclaw.data.ThemePreference
import ai.androidclaw.ui.components.ScreenHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenSetupGuide: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = "Settings",
            subtitle = "Choose providers, theme preferences, and connection defaults for AndroidClaw.",
            titleTestTag = "settingsHeading",
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemePreference.entries.forEach { themePreference ->
                        FilterChip(
                            selected = state.themePreference == themePreference,
                            onClick = { viewModel.selectThemePreference(themePreference) },
                            label = { Text(themePreference.displayName) },
                        )
                    }
                }
                Text("Current theme: ${state.themePreference.displayName}")
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Provider", style = MaterialTheme.typography.titleMedium)
                onOpenSetupGuide?.let { openSetupGuide ->
                    Button(onClick = openSetupGuide) {
                        Text("Run setup guide")
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.availableProviders.forEach { providerType ->
                        FilterChip(
                            modifier = Modifier.testTag("providerChip-${providerType.storageValue}"),
                            selected = state.providerType == providerType,
                            onClick = { viewModel.selectProviderType(providerType) },
                            label = { Text(providerType.displayName) },
                        )
                    }
                }
                Text(
                    text = "Active provider: ${state.activeProviderId}",
                    modifier = Modifier.testTag("activeProviderText"),
                )
                Text("Network: ${state.networkSummary}")
                state.connectionHint?.let { hint ->
                    Text(hint)
                }
                Text("Configured: ${state.configured}")
                if (state.providerType.requiresRemoteSettings) {
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = viewModel::onBaseUrlChanged,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("providerBaseUrlField"),
                        label = { Text("Base URL") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.modelId,
                        onValueChange = viewModel::onModelIdChanged,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("providerModelIdField"),
                        label = { Text("Model ID") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.timeoutSeconds,
                        onValueChange = viewModel::onTimeoutChanged,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("providerTimeoutField"),
                        label = { Text("Timeout seconds") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.apiKeyDraft,
                        onValueChange = viewModel::onApiKeyChanged,
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
                        Button(
                            onClick = viewModel::clearStoredApiKey,
                            modifier = Modifier.testTag("clearStoredApiKeyButton"),
                        ) {
                            Text("Clear stored API key")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.testTag("saveProviderSettingsButton"),
                        onClick = viewModel::save,
                        enabled = !state.isValidatingConnection,
                    ) {
                        Text("Save provider settings")
                    }
                    Button(
                        modifier = Modifier.testTag("testProviderConnectionButton"),
                        onClick = viewModel::validateConnection,
                        enabled = !state.isValidatingConnection,
                    ) {
                        Text(if (state.isValidatingConnection) "Testing…" else "Test connection")
                    }
                }
                state.statusMessage?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.testTag("settingsStatusMessage"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Build posture", style = MaterialTheme.typography.titleMedium)
                Text(state.buildPosture)
            }
        }
    }
}
