package ai.androidclaw.feature.chat

import ai.androidclaw.app.AndroidClawApplication
import ai.androidclaw.app.MainActivity
import ai.androidclaw.app.resetApplicationState
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.runtime.voice.FakeSpeechInput
import ai.androidclaw.runtime.voice.FakeSpeechOutput
import ai.androidclaw.runtime.voice.SpeechInput
import ai.androidclaw.runtime.voice.SpeechOutput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Voice paths with fake speech adapters: no recognizer activity, no TTS engine, no espresso-intents. */
@RunWith(AndroidJUnit4::class)
class ChatVoiceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var application: AndroidClawApplication
    private lateinit var systemInput: SpeechInput
    private lateinit var systemOutputFactory: () -> SpeechOutput
    private val fakeInput = FakeSpeechInput(transcript = "hello from voice")
    private val fakeOutput = FakeSpeechOutput()

    @Before
    fun setUp() {
        application =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .applicationContext as AndroidClawApplication
        resetApplicationState(application = application, onboardingCompleted = true)
        systemInput = application.container.speechInput
        systemOutputFactory = application.container.speechOutputFactory
        application.container.speechInput = fakeInput
        application.container.speechOutputFactory = { fakeOutput }
        setVoiceSettings(sendRightAway = false, voiceReplies = false)
    }

    @After
    fun tearDown() {
        application.container.speechInput = systemInput
        application.container.speechOutputFactory = systemOutputFactory
        setVoiceSettings(sendRightAway = false, voiceReplies = false)
        resetApplicationState(application = application, onboardingCompleted = true)
    }

    @Test
    fun micPutsTheTranscriptInTheComposerWithoutSending() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForTag("chatMicButton")
            composeRule.onNodeWithTag("chatMicButton").performClick()

            composeRule.waitUntil(timeoutMillis = 5_000) { inputText() == "hello from voice" }
            assertEquals(0, composeRule.onAllNodesWithTag("chatMicButton").fetchSemanticsNodes().size)
            assertTrue(fakeInput.requestedLanguageTags.single().contains('-'))
            assertTrue(storedMessages().none { it.role == MessageRole.User })
        }
    }

    @Test
    fun sendRightAwayRunsTheTurnAndVoiceRepliesReadTheReply() {
        setVoiceSettings(sendRightAway = true, voiceReplies = true)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForTag("chatMicButton")
            composeRule.onNodeWithTag("chatMicButton").performClick()

            composeRule.waitUntil(timeoutMillis = 15_000) {
                fakeOutput.spoken.isNotEmpty() &&
                    composeRule.onAllNodesWithTag("chatStopButton").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(storedMessages().any { it.role == MessageRole.User && it.content == "hello from voice" })
            assertTrue(
                fakeOutput.spoken
                    .single()
                    .chunks
                    .isNotEmpty(),
            )

            waitForTag("chatReadAloudBar")
            composeRule.onNodeWithTag("chatStopReadingButton").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("chatReadAloudBar").fetchSemanticsNodes().isEmpty()
            }
            // The mic never reopens by itself: one recognizer launch for the whole turn.
            assertEquals(1, fakeInput.requestedLanguageTags.size)
        }
    }

    @Test
    fun readAloudActionSpeaksAnAssistantMessage() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForTag("chatMicButton")
            composeRule.onNodeWithTag("chatMicButton").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) { inputText() == "hello from voice" }
            composeRule.onNodeWithTag("chatSendButton").performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithTag("chatReadAloudButton", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithTag("chatStopButton").fetchSemanticsNodes().isEmpty()
            }
            // Voice replies are off: nothing was spoken automatically.
            assertTrue(fakeOutput.spoken.isEmpty())

            composeRule.onAllNodesWithTag("chatReadAloudButton", useUnmergedTree = true).onFirst().performClick()
            waitForTag("chatReadAloudBar")
            assertEquals(1, fakeOutput.spoken.size)
        }
    }

    private fun inputText(): String? =
        composeRule
            .onAllNodesWithTag("chatInput")
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrElseNullable(SemanticsProperties.EditableText) { null }
            ?.text

    private fun storedMessages() =
        runBlocking {
            val mainSession = application.container.sessionRepository.getOrCreateMainSession()
            application.container.messageRepository
                .observeMessages(mainSession.id)
                .first()
        }

    private fun setVoiceSettings(
        sendRightAway: Boolean,
        voiceReplies: Boolean,
    ) = runBlocking {
        application.container.settingsDataStore.setVoiceSendRightAway(sendRightAway)
        application.container.settingsDataStore.setVoiceReplies(voiceReplies)
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
