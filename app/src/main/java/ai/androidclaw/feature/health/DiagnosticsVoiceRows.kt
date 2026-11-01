package ai.androidclaw.feature.health

import ai.androidclaw.R
import ai.androidclaw.runtime.voice.VoiceAvailability
import ai.androidclaw.runtime.voice.normalizeSpeechLocale
import ai.androidclaw.runtime.voice.probeVoiceAvailability
import ai.androidclaw.ui.components.ClawFactRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/** Diagnostics → Voice: recognizer, TTS engine and TTS support for the app language. Probed when shown. */
@Composable
internal fun VoiceDiagnosticsRows() {
    val context = LocalContext.current
    val appLocale = normalizeSpeechLocale(LocalConfiguration.current.locales[0])
    val availability by produceState<VoiceAvailability?>(initialValue = null, appLocale) {
        value = probeVoiceAvailability(context, appLocale)
    }
    val checking = stringResource(R.string.diag_voice_checking)
    val none = stringResource(R.string.diag_none)
    val current = availability
    ClawFactRow(stringResource(R.string.diag_voice_recognizer), current?.let { it.recognizerPackage ?: none } ?: checking)
    ClawFactRow(
        stringResource(R.string.diag_voice_tts),
        current?.let { it.ttsEnginePackages.joinToString().ifEmpty { none } } ?: checking,
    )
    val languageTag = appLocale.toLanguageTag()
    val supported = current?.ttsLanguages?.get(languageTag)
    ClawFactRow(
        stringResource(R.string.diag_voice_tts_language, languageTag),
        when {
            current == null -> checking
            supported == null -> none
            else -> stringResource(if (supported) R.string.diag_yes else R.string.diag_no)
        },
    )
}
