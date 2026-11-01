package ai.androidclaw.feature.chat

import ai.androidclaw.R
import ai.androidclaw.data.VoiceSettings
import ai.androidclaw.runtime.voice.RecognizeSpeechContract
import ai.androidclaw.runtime.voice.SpeechInput
import ai.androidclaw.runtime.voice.SpeechOutcome
import ai.androidclaw.runtime.voice.SpeechOutput
import ai.androidclaw.runtime.voice.SpeechRequest
import ai.androidclaw.runtime.voice.chooseSpeechLocale
import ai.androidclaw.runtime.voice.chunkForSpeech
import ai.androidclaw.runtime.voice.recognizerLanguageTag
import ai.androidclaw.runtime.voice.shouldReadReplyAloud
import ai.androidclaw.runtime.voice.speechTextFromMarkdown
import ai.androidclaw.ui.text.UiText
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class ChatVoiceUiState(
    val inputAvailable: Boolean = false,
    val voiceReplies: Boolean = false,
    val speakingMessageId: String? = null,
)

/**
 * Tap-to-talk and read-aloud for the chat screen. Owned by [ChatViewModel]:
 * the TTS engine is created on the first read-aloud and released by [release].
 */
class ChatVoiceController(
    private val scope: CoroutineScope,
    private val voiceSettings: Flow<VoiceSettings>,
    private val saveVoiceReplies: suspend (Boolean) -> Unit,
    private val speechInput: SpeechInput?,
    private val speechOutputFactory: (() -> SpeechOutput)?,
    private val onNotice: (UiText) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val inputAvailable = MutableStateFlow(false)
    private val speakingMessageId = MutableStateFlow<String?>(null)
    private var output: SpeechOutput? = null
    private var draftFromVoice = false
    private var turnStartedByVoice = false

    /** Effective app language, set by the chat screen from its configuration. */
    @Volatile var appLocale: Locale = Locale.getDefault()

    val contract: ActivityResultContract<String, String?> = speechInput?.contract ?: RecognizeSpeechContract()

    val state: StateFlow<ChatVoiceUiState> =
        combine(
            inputAvailable,
            voiceSettings.map { it.voiceReplies }.distinctUntilChanged(),
            speakingMessageId,
        ) { available, replies, speaking ->
            ChatVoiceUiState(inputAvailable = available, voiceReplies = replies, speakingMessageId = speaking)
        }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), ChatVoiceUiState())

    init {
        if (speechInput != null) {
            scope.launch {
                inputAvailable.value =
                    withContext(ioDispatcher) { runCatching { speechInput.recognizerPackage() != null }.getOrDefault(false) }
            }
        }
    }

    fun languageTag(): String = recognizerLanguageTag(appLocale)

    /** Mic tapped: stop reading so the recognizer does not hear the app. */
    fun beforeListening() = stopSpeaking()

    fun onListenFailed() {
        inputAvailable.value = false
        onNotice(UiText.res(R.string.chat_voice_unavailable))
    }

    /** Returns the trimmed transcript for the composer, or null when nothing was heard. */
    fun onTranscript(text: String?): String? {
        val transcript = text?.trim()?.takeIf(String::isNotEmpty) ?: return null
        draftFromVoice = true
        return transcript
    }

    fun onDraftEdited(value: String) {
        if (value.isBlank()) draftFromVoice = false
    }

    suspend fun sendRightAway(): Boolean = voiceSettings.first().sendRightAway

    fun onTurnStarted() {
        turnStartedByVoice = draftFromVoice
        draftFromVoice = false
        stopSpeaking()
    }

    fun onTurnCompleted(
        messageId: String?,
        text: String,
    ) {
        val byVoice = turnStartedByVoice
        turnStartedByVoice = false
        if (!byVoice) return
        scope.launch {
            val enabled = voiceSettings.first().voiceReplies
            if (shouldReadReplyAloud(enabled, byVoice, text)) readAloud(messageId ?: "voice-reply", text)
        }
    }

    fun onTurnEnded() {
        turnStartedByVoice = false
    }

    fun setVoiceReplies(enabled: Boolean) {
        scope.launch { saveVoiceReplies(enabled) }
    }

    fun toggleReadAloud(
        messageId: String,
        markdown: String,
    ) {
        if (speakingMessageId.value == messageId) stopSpeaking() else readAloud(messageId, markdown)
    }

    fun readAloud(
        messageId: String,
        markdown: String,
    ) {
        val engine = output ?: speechOutputFactory?.invoke()?.also { output = it } ?: return
        val locale = chooseSpeechLocale(markdown, appLocale)
        val chunks = chunkForSpeech(speechTextFromMarkdown(markdown, engine.codeBlockPhrase(locale)))
        if (chunks.isEmpty()) return
        engine.stop()
        speakingMessageId.value = messageId
        engine.speak(SpeechRequest(messageId, chunks, locale)) { outcome ->
            if (speakingMessageId.value == messageId) speakingMessageId.value = null
            when (outcome) {
                SpeechOutcome.LanguageUnsupported -> onNotice(UiText.res(R.string.voice_tts_language_unsupported))
                SpeechOutcome.EngineUnavailable -> onNotice(UiText.res(R.string.voice_tts_unavailable))
                SpeechOutcome.Failed -> onNotice(UiText.res(R.string.voice_tts_failed))
                SpeechOutcome.Done, SpeechOutcome.Stopped -> Unit
            }
        }
    }

    fun stopSpeaking() {
        output?.stop()
        speakingMessageId.value = null
    }

    fun release() {
        output?.shutdown()
        output = null
        speakingMessageId.value = null
    }
}
