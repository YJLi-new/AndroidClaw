package ai.androidclaw.feature.onboarding

import ai.androidclaw.data.ProviderAuthMode
import ai.androidclaw.data.ProviderType
import ai.androidclaw.feature.settings.SettingsUiState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingDialog(
    onboardingState: OnboardingUiState,
    settingsState: SettingsUiState,
    onConfigureRealProvider: () -> Unit,
    onUseFakeMode: () -> Unit,
    onCompleteLater: () -> Unit,
    onBackToWelcome: () -> Unit,
    onSelectProvider: (ProviderType) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onModelIdChanged: (String) -> Unit,
    onTimeoutChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onStartOpenAiCodexSignIn: () -> Unit,
    onCancelOpenAiCodexSignIn: () -> Unit,
    onClearOpenAiCodexSignIn: () -> Unit,
    onValidateConnection: () -> Unit,
    onFinish: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        modifier = Modifier.testTag("onboardingDialog"),
        onDismissRequest = {},
        title = {
            Text(
                if (onboardingState.isWelcomeStep) {
                    "Welcome to AndroidClaw"
                } else {
                    "Set up a real provider"
                },
            )
        },
        text = {
            if (onboardingState.isWelcomeStep) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "AndroidClaw is a local-first Android assistant host with chat sessions, tools, skills, and scheduled automations.",
                    )
                    Text("You can stay fully offline with FakeProvider, or configure a real model provider now.")
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Choose a provider, enter its settings, then run a connection test before finishing setup.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        settingsState.availableProviders
                            .filter { it.requiresRemoteSettings }
                            .forEach { providerType ->
                                FilterChip(
                                    modifier = Modifier.testTag("onboardingProviderChip-${providerType.storageValue}"),
                                    selected = settingsState.providerType == providerType,
                                    onClick = { onSelectProvider(providerType) },
                                    label = { Text(providerType.displayName) },
                                )
                            }
                    }
                    Text("Network: ${settingsState.networkSummary}")
                    OutlinedTextField(
                        value = settingsState.baseUrl,
                        onValueChange = onBaseUrlChanged,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("onboardingBaseUrlField"),
                        label = { Text("Base URL") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = settingsState.modelId,
                        onValueChange = onModelIdChanged,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("onboardingModelIdField"),
                        label = { Text("Model ID") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = settingsState.timeoutSeconds,
                        onValueChange = onTimeoutChanged,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("onboardingTimeoutField"),
                        label = { Text("Timeout seconds") },
                        singleLine = true,
                    )
                    when (settingsState.authMode) {
                        ProviderAuthMode.None -> Unit
                        ProviderAuthMode.ApiKey ->
                            OutlinedTextField(
                                value = settingsState.apiKeyDraft,
                                onValueChange = onApiKeyChanged,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("onboardingApiKeyField"),
                                label = {
                                    Text(
                                        if (settingsState.hasStoredApiKey) {
                                            "API key (leave blank to keep stored key)"
                                        } else {
                                            "API key"
                                        },
                                    )
                                },
                                singleLine = true,
                            )

                        ProviderAuthMode.OpenAiCodexDeviceCode -> {
                            Text(
                                if (settingsState.hasOAuthCredential) {
                                    "Signed in: ${settingsState.oAuthProfileLabel.orEmpty()}"
                                } else {
                                    "OpenAI Codex requires device-code sign-in."
                                },
                            )
                            settingsState.deviceCodeUserCode?.let { code ->
                                Text(
                                    text = "Code: $code",
                                    modifier = Modifier.testTag("onboardingOpenAiCodexDeviceCode"),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            settingsState.deviceCodeVerificationUrl?.let { url ->
                                Button(
                                    onClick = { uriHandler.openUri(url) },
                                    modifier = Modifier.testTag("onboardingOpenAiCodexOpenVerificationButton"),
                                ) {
                                    Text("Open verification page")
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = onStartOpenAiCodexSignIn,
                                    enabled = !settingsState.isSigningInWithOpenAiCodex,
                                    modifier = Modifier.testTag("onboardingOpenAiCodexSignInButton"),
                                ) {
                                    Text(
                                        if (settingsState.isSigningInWithOpenAiCodex) {
                                            "Signing in..."
                                        } else {
                                            "Sign in"
                                        },
                                    )
                                }
                                if (settingsState.isSigningInWithOpenAiCodex) {
                                    Button(onClick = onCancelOpenAiCodexSignIn) {
                                        Text("Cancel")
                                    }
                                }
                                if (settingsState.hasOAuthCredential) {
                                    Button(onClick = onClearOpenAiCodexSignIn) {
                                        Text("Clear sign-in")
                                    }
                                }
                            }
                        }
                    }
                    settingsState.statusMessage?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.testTag("onboardingStatusMessage"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (onboardingState.isWelcomeStep) {
                Button(
                    onClick = onUseFakeMode,
                    modifier = Modifier.testTag("onboardingUseFakeButton"),
                ) {
                    Text("Use Fake (offline)")
                }
            } else {
                Button(
                    modifier = Modifier.testTag("onboardingFinishButton"),
                    onClick = onFinish,
                    enabled = settingsState.lastValidationSucceeded,
                ) {
                    Text("Finish")
                }
            }
        },
        dismissButton = {
            if (onboardingState.isWelcomeStep) {
                Column(
                    modifier = Modifier.padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onConfigureRealProvider,
                        modifier = Modifier.testTag("onboardingConfigureRealProviderButton"),
                    ) {
                        Text("Configure real provider")
                    }
                    Button(
                        onClick = onCompleteLater,
                        modifier = Modifier.testTag("onboardingCompleteLaterButton"),
                    ) {
                        Text("Complete setup later")
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.testTag("onboardingTestConnectionButton"),
                        onClick = onValidateConnection,
                        enabled = !settingsState.isValidatingConnection,
                    ) {
                        Text(if (settingsState.isValidatingConnection) "Testing…" else "Test connection")
                    }
                    Button(
                        onClick = onBackToWelcome,
                        modifier = Modifier.testTag("onboardingBackButton"),
                    ) {
                        Text("Back")
                    }
                }
            }
        },
    )
}
