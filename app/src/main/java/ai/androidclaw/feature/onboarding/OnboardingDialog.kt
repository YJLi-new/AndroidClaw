package ai.androidclaw.feature.onboarding

import ai.androidclaw.R
import ai.androidclaw.data.ProviderType
import ai.androidclaw.feature.settings.ProviderFieldCallbacks
import ai.androidclaw.feature.settings.ProviderFields
import ai.androidclaw.feature.settings.SettingsUiState
import ai.androidclaw.feature.settings.providerLabel
import ai.androidclaw.ui.components.ClawNotice
import ai.androidclaw.ui.components.NoticeTone
import ai.androidclaw.ui.text.asString
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
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
                stringResource(
                    if (onboardingState.isWelcomeStep) R.string.onboarding_welcome_title else R.string.onboarding_setup_title,
                ),
            )
        },
        text = {
            if (onboardingState.isWelcomeStep) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.onboarding_welcome_body))
                    Text(
                        text = stringResource(R.string.onboarding_welcome_choice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onUseFakeMode,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("onboardingUseFakeButton"),
                        ) {
                            Text(stringResource(R.string.onboarding_use_demo))
                        }
                        OutlinedButton(
                            onClick = onConfigureRealProvider,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("onboardingConfigureRealProviderButton"),
                        ) {
                            Text(stringResource(R.string.onboarding_configure_provider))
                        }
                        TextButton(
                            onClick = onCompleteLater,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("onboardingCompleteLaterButton"),
                        ) {
                            Text(stringResource(R.string.onboarding_later))
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_setup_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        settingsState.availableProviders
                            .filter { it.requiresRemoteSettings }
                            .forEach { providerType ->
                                FilterChip(
                                    modifier = Modifier.testTag("onboardingProviderChip-${providerType.storageValue}"),
                                    selected = settingsState.providerType == providerType,
                                    onClick = { onSelectProvider(providerType) },
                                    label = { Text(providerLabel(providerType)) },
                                )
                            }
                    }
                    if (settingsState.providerType.requiresRemoteSettings) {
                        ProviderFields(
                            state = settingsState,
                            callbacks =
                                ProviderFieldCallbacks(
                                    onBaseUrlChanged = onBaseUrlChanged,
                                    onModelIdChanged = onModelIdChanged,
                                    onTimeoutChanged = onTimeoutChanged,
                                    onApiKeyChanged = onApiKeyChanged,
                                    onClearApiKey = null,
                                    onStartCodexSignIn = onStartOpenAiCodexSignIn,
                                    onCancelCodexSignIn = onCancelOpenAiCodexSignIn,
                                    onClearCodexSignIn = onClearOpenAiCodexSignIn,
                                    onOpenVerificationUrl = { url -> runCatching { uriHandler.openUri(url) } },
                                ),
                            fieldTagPrefix = "onboarding",
                            codexTagPrefix = "onboarding",
                        )
                    }
                    settingsState.statusMessage?.let { message ->
                        ClawNotice(
                            text = message.asString(),
                            modifier = Modifier.testTag("onboardingStatusMessage"),
                            tone = if (settingsState.lastValidationSucceeded) NoticeTone.Success else NoticeTone.Info,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.testTag("onboardingTestConnectionButton"),
                            onClick = onValidateConnection,
                            enabled = !settingsState.isValidatingConnection,
                        ) {
                            Text(
                                stringResource(
                                    if (settingsState.isValidatingConnection) R.string.settings_testing else R.string.settings_test,
                                ),
                            )
                        }
                        TextButton(
                            onClick = onBackToWelcome,
                            modifier = Modifier.testTag("onboardingBackButton"),
                        ) {
                            Text(stringResource(R.string.action_back))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!onboardingState.isWelcomeStep) {
                Button(
                    modifier = Modifier.testTag("onboardingFinishButton"),
                    onClick = onFinish,
                    enabled = settingsState.lastValidationSucceeded,
                ) {
                    Text(stringResource(R.string.onboarding_finish))
                }
            }
        },
    )
}
