package ai.androidclaw.runtime.voice

import ai.androidclaw.R
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

internal const val VOICE_LOG_TAG = "AndroidClawVoice"

enum class SpeechOutcome {
    Done,
    Stopped,
    Failed,
    LanguageUnsupported,
    EngineUnavailable,
}

data class SpeechRequest(
    val id: String,
    val chunks: List<String>,
    val locale: Locale,
)

/** Read-aloud output. Calls and callbacks run on the main thread. */
interface SpeechOutput {
    /** Replaces anything playing. [onFinished] runs exactly once per request. */
    fun speak(
        request: SpeechRequest,
        onFinished: (SpeechOutcome) -> Unit,
    )

    fun stop()

    fun shutdown()

    /** Short phrase spoken in place of a code block, in [locale]. */
    fun codeBlockPhrase(locale: Locale): String
}

/**
 * Platform [TextToSpeech]. The engine is bound on the first [speak], never at
 * startup, and released by [shutdown] when the chat ViewModel is cleared.
 */
class SystemSpeechOutput(
    context: Context,
) : SpeechOutput {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var initState = InitState.NotStarted
    private var queued: Playback? = null
    private var current: Playback? = null
    private var sequence = 0

    private enum class InitState { NotStarted, Pending, Ready, Failed }

    private class Playback(
        val request: SpeechRequest,
        val utterancePrefix: String,
        private val onFinished: (SpeechOutcome) -> Unit,
    ) {
        private var finished = false
        val lastUtteranceId: String = "$utterancePrefix${request.chunks.lastIndex}"

        fun finish(outcome: SpeechOutcome) {
            if (finished) return
            finished = true
            onFinished(outcome)
        }
    }

    private val progressListener =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    val playback = current ?: return@post
                    if (utteranceId != playback.lastUtteranceId) return@post
                    current = null
                    Log.i(
                        VOICE_LOG_TAG,
                        "TTS_DONE locale=${playback.request.locale.toLanguageTag()} chunks=${playback.request.chunks.size}",
                    )
                    playback.finish(SpeechOutcome.Done)
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                mainHandler.post { failCurrent(utteranceId) }
            }

            override fun onError(
                utteranceId: String?,
                errorCode: Int,
            ) {
                mainHandler.post { failCurrent(utteranceId) }
            }
        }

    override fun speak(
        request: SpeechRequest,
        onFinished: (SpeechOutcome) -> Unit,
    ) {
        stop()
        sequence += 1
        val playback = Playback(request, "tts$sequence#", onFinished)
        if (request.chunks.isEmpty()) {
            playback.finish(SpeechOutcome.Done)
            return
        }
        when (initState) {
            InitState.Ready -> start(playback)
            InitState.Failed -> playback.finish(SpeechOutcome.EngineUnavailable)
            InitState.Pending -> queued = playback
            InitState.NotStarted -> {
                queued = playback
                initState = InitState.Pending
                engine = TextToSpeech(appContext) { status -> mainHandler.post { onEngineReady(status) } }
            }
        }
    }

    override fun stop() {
        queued?.let { playback ->
            queued = null
            playback.finish(SpeechOutcome.Stopped)
        }
        current?.let { playback ->
            current = null
            engine?.stop()
            playback.finish(SpeechOutcome.Stopped)
        }
    }

    override fun shutdown() {
        stop()
        engine?.shutdown()
        engine = null
        initState = InitState.NotStarted
    }

    override fun codeBlockPhrase(locale: Locale): String {
        val configuration = Configuration(appContext.resources.configuration).apply { setLocale(locale) }
        return appContext.createConfigurationContext(configuration).getString(R.string.voice_code_block_omitted)
    }

    private fun onEngineReady(status: Int) {
        val tts = engine
        if (initState != InitState.Pending || tts == null) return
        if (status != TextToSpeech.SUCCESS) {
            initState = InitState.Failed
            Log.w(VOICE_LOG_TAG, "TTS_INIT_FAILED status=$status")
            queued?.finish(SpeechOutcome.EngineUnavailable)
            queued = null
            return
        }
        initState = InitState.Ready
        tts.setOnUtteranceProgressListener(progressListener)
        queued?.let { playback ->
            queued = null
            start(playback)
        }
    }

    private fun start(playback: Playback) {
        val tts = engine ?: return playback.finish(SpeechOutcome.EngineUnavailable)
        val languageResult = tts.setLanguage(playback.request.locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(VOICE_LOG_TAG, "TTS_LANGUAGE_UNSUPPORTED locale=${playback.request.locale.toLanguageTag()}")
            playback.finish(SpeechOutcome.LanguageUnsupported)
            return
        }
        current = playback
        playback.request.chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(chunk, mode, null, "${playback.utterancePrefix}$index") != TextToSpeech.SUCCESS) {
                current = null
                tts.stop()
                playback.finish(SpeechOutcome.Failed)
                return
            }
        }
    }

    private fun failCurrent(utteranceId: String?) {
        val playback = current ?: return
        if (utteranceId?.startsWith(playback.utterancePrefix) != true) return
        current = null
        Log.w(VOICE_LOG_TAG, "TTS_ERROR locale=${playback.request.locale.toLanguageTag()}")
        playback.finish(SpeechOutcome.Failed)
    }
}
