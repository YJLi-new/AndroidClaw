package ai.androidclaw.feature.settings

import ai.androidclaw.R
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.VoiceSettings
import ai.androidclaw.runtime.voice.resolveRecognizerPackage
import ai.androidclaw.ui.components.ClawListItem
import ai.androidclaw.ui.components.ClawSectionHeader
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Voice. Owned by [SettingsViewModel]. */
class VoiceSettingsModel(
    private val scope: CoroutineScope,
    private val settingsDataStore: SettingsDataStore,
) {
    val state: StateFlow<VoiceSettings> =
        settingsDataStore.voiceSettings.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), VoiceSettings())

    fun setSendRightAway(enabled: Boolean) {
        scope.launch { settingsDataStore.setVoiceSendRightAway(enabled) }
    }
}

@Composable
fun VoiceSection(model: VoiceSettingsModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val recognizerFound by produceState<Boolean?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { resolveRecognizerPackage(context.packageManager) != null }
    }
    ClawSectionHeader(stringResource(R.string.settings_section_voice))
    ClawListItem(
        title = stringResource(R.string.settings_voice_send_right_away),
        supporting = stringResource(R.string.settings_voice_send_right_away_hint),
        leadingIcon = R.drawable.ic_mic,
        onClick = { model.setSendRightAway(!state.sendRightAway) },
        modifier = Modifier.testTag("voiceSendRightAwayItem"),
        trailing = {
            Switch(
                checked = state.sendRightAway,
                onCheckedChange = model::setSendRightAway,
                modifier = Modifier.testTag("voiceSendRightAwaySwitch"),
            )
        },
    )
    Text(
        text =
            stringResource(
                if (recognizerFound == false) R.string.settings_voice_no_recognizer else R.string.settings_voice_replies_hint,
            ),
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
