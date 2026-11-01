package ai.androidclaw.runtime.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Tap-to-talk input through the system recognizer UI. The recognizer owns the
 * microphone, so the app needs no RECORD_AUDIO permission.
 */
interface SpeechInput {
    /** Package that handles RECOGNIZE_SPEECH, or null. Queries the package manager: call off the main thread. */
    fun recognizerPackage(): String?

    /** Input: BCP 47 language tag. Output: the best transcript, or null when cancelled or nothing was heard. */
    val contract: ActivityResultContract<String, String?>
}

class SystemSpeechInput(
    private val context: Context,
) : SpeechInput {
    override fun recognizerPackage(): String? = resolveRecognizerPackage(context.packageManager)

    override val contract: ActivityResultContract<String, String?> = RecognizeSpeechContract()
}

class RecognizeSpeechContract : ActivityResultContract<String, String?>() {
    override fun createIntent(
        context: Context,
        input: String,
    ): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, input)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): String? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }
}

internal fun resolveRecognizerPackage(packageManager: PackageManager): String? {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    val matches =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
    return matches.firstOrNull()?.activityInfo?.packageName
}
