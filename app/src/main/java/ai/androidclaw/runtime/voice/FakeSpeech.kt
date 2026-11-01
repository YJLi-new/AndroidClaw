package ai.androidclaw.runtime.voice

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Test double: returns [transcript] synchronously, so no recognizer activity starts. */
class FakeSpeechInput(
    @Volatile var transcript: String? = null,
    @Volatile var available: Boolean = true,
) : SpeechInput {
    val requestedLanguageTags: MutableList<String> = CopyOnWriteArrayList()

    override fun recognizerPackage(): String? = if (available) "ai.androidclaw.fake.recognizer" else null

    override val contract: ActivityResultContract<String, String?> =
        object : ActivityResultContract<String, String?>() {
            override fun createIntent(
                context: Context,
                input: String,
            ): Intent = Intent("ai.androidclaw.fake.RECOGNIZE_SPEECH")

            override fun getSynchronousResult(
                context: Context,
                input: String,
            ): SynchronousResult<String?> {
                requestedLanguageTags += input
                return SynchronousResult(transcript)
            }

            override fun parseResult(
                resultCode: Int,
                intent: Intent?,
            ): String? = null
        }
}

/** Test double: records requests and stays "speaking" until [finish] or [stop]. */
class FakeSpeechOutput : SpeechOutput {
    val spoken: MutableList<SpeechRequest> = CopyOnWriteArrayList()

    @Volatile var stopCount = 0
        private set

    @Volatile var shutdownCount = 0
        private set
    private var pending: ((SpeechOutcome) -> Unit)? = null

    override fun speak(
        request: SpeechRequest,
        onFinished: (SpeechOutcome) -> Unit,
    ) {
        stop()
        spoken += request
        pending = onFinished
    }

    fun finish(outcome: SpeechOutcome = SpeechOutcome.Done) {
        val callback = pending ?: return
        pending = null
        callback(outcome)
    }

    override fun stop() {
        stopCount += 1
        val callback = pending ?: return
        pending = null
        callback(SpeechOutcome.Stopped)
    }

    override fun shutdown() {
        stop()
        shutdownCount += 1
    }

    override fun codeBlockPhrase(locale: Locale): String = "[code]"
}
