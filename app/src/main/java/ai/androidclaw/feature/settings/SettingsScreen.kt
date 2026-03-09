package ai.androidclaw.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Provider", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.availableProviders.forEach { providerType ->
                        FilterChip(
                            selected = state.providerType == providerType,
                            onClick = { viewModel.selectProviderType(providerType) },
                            label = { Text(providerType.displayName) },
                        )
                    }
                }
                Text("Active provider: ${state.activeProviderId}")
                Text("Configured: ${state.configured}")
                if (state.providerType == ai.androidclaw.data.ProviderType.OpenAiCompatible) {
                    OutlinedTextField(
                        value = state.openAiBaseUrl,
                        onValueChange = viewModel::onOpenAiBaseUrlChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.openAiModelId,
                        onValueChange = viewModel::onOpenAiModelIdChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model ID") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.openAiTimeoutSeconds,
                        onValueChange = viewModel::onOpenAiTimeoutChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Timeout seconds") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.apiKeyDraft,
                        onValueChange = viewModel::onApiKeyChanged,
                        modifier = Modifier.fillMaxWidth(),
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
                        Button(onClick = viewModel::clearStoredApiKey) {
                            Text("Clear stored API key")
                        }
                    }
                }
                Button(onClick = viewModel::save) {
                    Text("Save provider settings")
                }
                state.statusMessage?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodyMedium)
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
