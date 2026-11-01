package ai.androidclaw.feature.chat

import ai.androidclaw.R
import ai.androidclaw.data.VoiceSettings
import ai.androidclaw.runtime.voice.FakeSpeechInput
import ai.androidclaw.runtime.voice.FakeSpeechOutput
import ai.androidclaw.runtime.voice.SpeechOutcome
import ai.androidclaw.ui.text.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class ChatVoiceControllerTest {
    private val settings = MutableStateFlow(VoiceSettings())
    private val output = FakeSpeechOutput()
    private val notices = mutableListOf<UiText>()
    private var outputsCreated = 0

    private fun TestScope.controller(input: FakeSpeechInput? = FakeSpeechInput()): ChatVoiceController {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return ChatVoiceController(
            scope = TestScope(dispatcher),
            voiceSettings = settings,
            saveVoiceReplies = { enabled -> settings.value = settings.value.copy(voiceReplies = enabled) },
            speechInput = input,
            speechOutputFactory = {
                outputsCreated += 1
                output
            },
            onNotice = { notices += it },
            ioDispatcher = dispatcher,
        ).also { it.appLocale = Locale.ENGLISH }
    }

    @Test
    fun `mic availability follows the recognizer and TTS is not created up front`() =
        runTest {
            val available = controller()
            val missing = controller(FakeSpeechInput(available = false))
            val none = controller(input = null)
            val states = mutableListOf<ChatVoiceUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { available.state.collect { states += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { missing.state.collect { } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { none.state.collect { } }

            assertTrue(available.state.value.inputAvailable)
            assertFalse(missing.state.value.inputAvailable)
            assertFalse(none.state.value.inputAvailable)
            assertEquals("en-US", available.languageTag())
            assertEquals(0, outputsCreated)
        }

    @Test
    fun `reply to a voice turn is read aloud when voice replies are on`() =
        runTest {
            settings.value = VoiceSettings(voiceReplies = true)
            val voice = controller()

            assertEquals("hello", voice.onTranscript("  hello "))
            voice.onDraftEdited("hello")
            voice.onTurnStarted()
            voice.onTurnCompleted("msg-1", "**Sunny** today.\n```\ncode\n```")

            assertEquals(1, output.spoken.size)
            val request = output.spoken.single()
            assertEquals("msg-1", request.id)
            assertEquals(listOf("Sunny today.\n[code]"), request.chunks)
            assertEquals(Locale.US, request.locale)
        }

    @Test
    fun `typed turns and voice replies off never speak`() =
        runTest {
            val voice = controller()
            settings.value = VoiceSettings(voiceReplies = true)
            voice.onTurnStarted()
            voice.onTurnCompleted("typed", "Reply")

            settings.value = VoiceSettings(voiceReplies = false)
            voice.onTranscript("spoken")
            voice.onTurnStarted()
            voice.onTurnCompleted("voice-off", "Reply")

            assertTrue(output.spoken.isEmpty())
            assertEquals(0, outputsCreated)
        }

    @Test
    fun `clearing the composer drops the voice mark`() =
        runTest {
            settings.value = VoiceSettings(voiceReplies = true)
            val voice = controller()
            voice.onTranscript("spoken")
            voice.onDraftEdited("")
            voice.onTurnStarted()
            voice.onTurnCompleted("m", "Reply")

            assertTrue(output.spoken.isEmpty())
        }

    @Test
    fun `nothing heard leaves the composer alone`() =
        runTest {
            val voice = controller()

            assertNull(voice.onTranscript(null))
            assertNull(voice.onTranscript("   "))
        }

    @Test
    fun `read aloud toggles to stop and tracks the speaking message`() =
        runTest {
            val voice = controller()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { voice.state.collect { } }

            voice.toggleReadAloud("a", "Hello")
            assertEquals("a", voice.state.value.speakingMessageId)

            voice.toggleReadAloud("a", "Hello")
            assertNull(voice.state.value.speakingMessageId)
            assertTrue(output.stopCount > 0)

            voice.toggleReadAloud("b", "今天天气晴朗。")
            assertEquals(Locale.SIMPLIFIED_CHINESE, output.spoken.last().locale)
            output.finish(SpeechOutcome.Done)
            assertNull(voice.state.value.speakingMessageId)
            assertEquals(1, outputsCreated)

            voice.release()
            assertEquals(1, output.shutdownCount)
        }

    @Test
    fun `engine problems show a notice and the mic tap stops speech`() =
        runTest {
            val voice = controller()
            voice.readAloud("a", "Hello")
            output.finish(SpeechOutcome.LanguageUnsupported)
            assertEquals(listOf(UiText.res(R.string.voice_tts_language_unsupported)), notices)

            voice.readAloud("b", "Hello")
            val stopsBefore = output.stopCount
            voice.beforeListening()
            assertTrue(output.stopCount > stopsBefore)
        }

    @Test
    fun `voice replies toggle is saved`() =
        runTest {
            val voice = controller()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { voice.state.collect { } }

            voice.setVoiceReplies(true)

            assertTrue(settings.value.voiceReplies)
            assertTrue(voice.state.value.voiceReplies)
        }
}
