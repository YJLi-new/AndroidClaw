package ai.androidclaw.feature.settings

import ai.androidclaw.R
import ai.androidclaw.data.BRAVE_API_KEY_MAX_CHARS
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.WebSearchKeyStore
import ai.androidclaw.runtime.tools.BraveSearchClient
import ai.androidclaw.runtime.tools.BraveSearchOutcome
import ai.androidclaw.runtime.tools.WebSearchMode
import ai.androidclaw.runtime.tools.WebSearchOffReason
import ai.androidclaw.runtime.tools.WebSearchResolution
import ai.androidclaw.runtime.tools.resolveWebSearchMode
import ai.androidclaw.ui.components.ClawNotice
import ai.androidclaw.ui.components.NoticeTone
import ai.androidclaw.ui.text.UiText
import ai.androidclaw.ui.text.asString
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val BRAVE_TEST_QUERY = "Android"

data class WebSearchSettingsState(
    val enabled: Boolean = true,
    val hasStoredKey: Boolean = false,
    val keyInput: String = "",
    val providerType: ProviderType = ProviderType.Fake,
    val resolution: WebSearchResolution = WebSearchResolution.off(WebSearchOffReason.ProviderHasNoHostedSearch),
    val testing: Boolean = false,
    val status: UiText? = null,
    val statusTone: NoticeTone = NoticeTone.Info,
)

private data class WebSearchLocalState(
    val keyInput: String = "",
    val testing: Boolean = false,
    val status: UiText? = null,
    val statusTone: NoticeTone = NoticeTone.Info,
)

/**
 * Settings → Web search. Owned by [SettingsViewModel]; the Brave key is read only once the
 * section is collected, so opening the app never touches the encrypted store.
 */
class WebSearchSettingsModel(
    private val scope: CoroutineScope,
    private val settingsDataStore: SettingsDataStore,
    private val keyStore: WebSearchKeyStore,
    private val braveSearchClient: BraveSearchClient,
) {
    private val keyPresent = MutableStateFlow<Boolean?>(null)
    private val local = MutableStateFlow(WebSearchLocalState())

    val state: StateFlow<WebSearchSettingsState> =
        combine(
            settingsDataStore.settings,
            settingsDataStore.webSearchEnabled,
            keyPresent,
            local,
        ) { settings, enabled, hasKey, localState ->
            val providerType = settings.providerType
            WebSearchSettingsState(
                enabled = enabled,
                hasStoredKey = hasKey == true,
                keyInput = localState.keyInput,
                providerType = providerType,
                resolution =
                    resolveWebSearchMode(
                        enabled = enabled,
                        hasBraveKey = hasKey == true,
                        providerType = providerType,
                        baseUrl = settings.endpointSettings(providerType).baseUrl,
                    ),
                testing = localState.testing,
                status = localState.status,
                statusTone = localState.statusTone,
            )
        }.onStart {
            if (keyPresent.value == null) {
                scope.launch { keyPresent.value = !keyStore.readBraveApiKey().isNullOrBlank() }
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), WebSearchSettingsState())

    fun setEnabled(enabled: Boolean) {
        scope.launch { settingsDataStore.setWebSearchEnabled(enabled) }
    }

    fun onKeyChanged(value: String) {
        local.update { it.copy(keyInput = value.take(BRAVE_API_KEY_MAX_CHARS), status = null) }
    }

    fun saveKey() {
        val key = local.value.keyInput.trim()
        if (key.isEmpty()) return
        scope.launch {
            keyStore.writeBraveApiKey(key)
            keyPresent.value = true
            local.update {
                it.copy(keyInput = "", status = UiText.res(R.string.settings_web_search_key_saved), statusTone = NoticeTone.Success)
            }
        }
    }

    fun clearKey() {
        scope.launch {
            keyStore.writeBraveApiKey(null)
            keyPresent.value = false
            local.update {
                it.copy(keyInput = "", status = UiText.res(R.string.settings_web_search_key_cleared), statusTone = NoticeTone.Info)
            }
        }
    }

    /** Runs one Brave query with the typed key (or the saved one) and reports the outcome. */
    fun testKey() {
        if (local.value.testing) return
        scope.launch {
            val key =
                local.value.keyInput
                    .trim()
                    .ifEmpty { keyStore.readBraveApiKey().orEmpty() }
            if (key.isEmpty()) {
                local.update { it.copy(status = UiText.res(R.string.settings_web_search_key_missing), statusTone = NoticeTone.Warning) }
                return@launch
            }
            local.update { it.copy(testing = true, status = null) }
            val outcome = braveSearchClient.search(apiKey = key, query = BRAVE_TEST_QUERY, count = 1)
            local.update {
                when (outcome) {
                    is BraveSearchOutcome.Success ->
                        it.copy(
                            testing = false,
                            status = UiText.res(R.string.settings_web_search_test_ok),
                            statusTone = NoticeTone.Success,
                        )

                    is BraveSearchOutcome.Failure ->
                        it.copy(
                            testing = false,
                            status = UiText.res(R.string.settings_web_search_test_failed, outcome.message),
                            statusTone = NoticeTone.Error,
                        )
                }
            }
        }
    }
}

@Composable
internal fun WebSearchSection(model: WebSearchSettingsModel) {
    val state by model.state.collectAsStateWithLifecycle()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("webSearchSection"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(true, false).forEachIndexed { index, enabled ->
                SegmentedButton(
                    selected = state.enabled == enabled,
                    onClick = { model.setEnabled(enabled) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    modifier = Modifier.testTag(if (enabled) "webSearchAutomatic" else "webSearchOff"),
                ) {
                    Text(stringResource(if (enabled) R.string.settings_web_search_automatic else R.string.settings_web_search_off))
                }
            }
        }
        Text(
            text = webSearchModeText(state),
            modifier = Modifier.testTag("webSearchModeText"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.keyInput,
            onValueChange = model::onKeyChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("webSearchBraveKeyField"),
            label = { Text(stringResource(R.string.settings_web_search_brave_key)) },
            placeholder = {
                if (state.hasStoredKey) Text(stringResource(R.string.settings_web_search_key_stored_placeholder))
            },
            supportingText = { Text(stringResource(R.string.settings_web_search_brave_help)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = model::saveKey,
                enabled = state.keyInput.isNotBlank(),
                modifier = Modifier.testTag("webSearchSaveKey"),
            ) { Text(stringResource(R.string.settings_web_search_save_key)) }
            OutlinedButton(
                onClick = model::testKey,
                enabled = !state.testing && (state.keyInput.isNotBlank() || state.hasStoredKey),
                modifier = Modifier.testTag("webSearchTestKey"),
            ) {
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.settings_web_search_test))
                }
            }
            if (state.hasStoredKey) {
                TextButton(
                    onClick = model::clearKey,
                    modifier = Modifier.testTag("webSearchClearKey"),
                ) { Text(stringResource(R.string.settings_web_search_clear_key)) }
            }
        }
        state.status?.let { status ->
            ClawNotice(
                text = status.asString(),
                tone = state.statusTone,
                modifier = Modifier.testTag("webSearchStatus"),
            )
        }
    }
}

@Composable
private fun webSearchModeText(state: WebSearchSettingsState): String {
    val provider = providerLabel(state.providerType)
    return when (state.resolution.mode) {
        WebSearchMode.Typed -> stringResource(R.string.settings_web_search_mode_typed)
        WebSearchMode.Hosted -> stringResource(R.string.settings_web_search_mode_hosted, provider)
        WebSearchMode.Off ->
            when (state.resolution.offReason) {
                WebSearchOffReason.DisabledInSettings -> stringResource(R.string.settings_web_search_mode_off_disabled)
                else -> stringResource(R.string.settings_web_search_mode_off_no_hosted, provider)
            }
    }
}
