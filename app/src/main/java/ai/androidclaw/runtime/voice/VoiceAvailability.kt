package ai.androidclaw.runtime.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

private const val TTS_PROBE_TIMEOUT_MS = 5_000L

data class VoiceAvailability(
    val recognizerPackage: String?,
    val ttsEnginePackages: List<String>,
    /** Language tag → available; empty when no engine answered. */
    val ttsLanguages: Map<String, Boolean>,
)

/**
 * Diagnostics probe: recognizer and TTS engines, and TTS support for the app
 * language plus en-US and zh-CN. Binds a short-lived TTS engine; never runs at startup.
 */
suspend fun probeVoiceAvailability(
    context: Context,
    appLocale: Locale,
): VoiceAvailability {
    val appContext = context.applicationContext
    val (recognizer, engines) =
        withContext(Dispatchers.IO) {
            resolveRecognizerPackage(appContext.packageManager) to ttsEnginePackages(appContext.packageManager)
        }
    val locales = listOf(normalizeSpeechLocale(appLocale), Locale.US, Locale.SIMPLIFIED_CHINESE).distinct()
    val languages =
        if (engines.isEmpty()) {
            emptyMap()
        } else {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(TTS_PROBE_TIMEOUT_MS) { ttsLanguageSupport(appContext, locales) }
            }.orEmpty()
        }
    Log.i(
        VOICE_LOG_TAG,
        "VOICE_PROBE recognizer=${recognizer ?: "none"} tts=${engines.joinToString().ifEmpty { "none" }} " +
            languages.entries.joinToString(" ") { "${it.key}=${it.value}" },
    )
    return VoiceAvailability(recognizer, engines, languages)
}

private fun ttsEnginePackages(packageManager: PackageManager): List<String> {
    val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
    val services =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, 0)
        }
    return services.mapNotNull { it.serviceInfo?.packageName }.distinct()
}

private suspend fun ttsLanguageSupport(
    context: Context,
    locales: List<Locale>,
): Map<String, Boolean> =
    suspendCancellableCoroutine { continuation ->
        var engine: TextToSpeech? = null
        engine =
            TextToSpeech(context) { status ->
                val tts = engine
                val support =
                    if (status == TextToSpeech.SUCCESS && tts != null) {
                        locales.associate { locale ->
                            locale.toLanguageTag() to (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE)
                        }
                    } else {
                        emptyMap()
                    }
                tts?.shutdown()
                if (continuation.isActive) continuation.resume(support)
            }
        continuation.invokeOnCancellation { engine?.shutdown() }
    }
