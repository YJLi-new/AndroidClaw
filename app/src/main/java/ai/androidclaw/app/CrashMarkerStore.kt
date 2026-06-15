package ai.androidclaw.app

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

internal const val CRASH_MARKER_THREAD_NAME_MAX_CHARS = 160
internal const val CRASH_MARKER_EXCEPTION_TYPE_MAX_CHARS = 256
internal const val CRASH_MARKER_MESSAGE_MAX_CHARS = 1_000
internal const val CRASH_MARKER_STACKTRACE_MAX_CHARS = 8_000
internal const val CRASH_MARKER_PREFERENCES_NAME = "androidclaw_crash_markers"
internal const val CRASH_MARKER_KEY_LAST_CRASH = "last_crash"
internal const val CRASH_MARKER_UNKNOWN_THREAD = "unknown"
internal const val CRASH_MARKER_UNKNOWN_EXCEPTION = "unknown"

@Serializable
data class CrashMarker(
    val timestampEpochMillis: Long,
    val threadName: String,
    val exceptionType: String,
    val message: String? = null,
    val stackTrace: String,
) {
    val timestamp: Instant
        get() = Instant.ofEpochMilli(timestampEpochMillis)
}

class CrashMarkerStore(
    context: Context,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    private val preferences =
        context.getSharedPreferences(CRASH_MARKER_PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun record(
        threadName: String,
        throwable: Throwable,
        timestamp: Instant = Instant.now(),
    ) {
        val marker =
            CrashMarker(
                timestampEpochMillis = timestamp.toEpochMilli(),
                threadName =
                    threadName
                        .toBoundedCrashMarkerText(CRASH_MARKER_THREAD_NAME_MAX_CHARS)
                        ?: CRASH_MARKER_UNKNOWN_THREAD,
                exceptionType =
                    throwable::class.java.name
                        .toBoundedCrashMarkerText(CRASH_MARKER_EXCEPTION_TYPE_MAX_CHARS)
                        ?: CRASH_MARKER_UNKNOWN_EXCEPTION,
                message = throwable.message.toBoundedCrashMarkerText(CRASH_MARKER_MESSAGE_MAX_CHARS),
                stackTrace = throwable.stackTraceToString().take(CRASH_MARKER_STACKTRACE_MAX_CHARS),
            )
        preferences
            .edit()
            .putString(CRASH_MARKER_KEY_LAST_CRASH, json.encodeToString(CrashMarker.serializer(), marker))
            .commit()
    }

    fun read(): CrashMarker? {
        val rawValue = preferences.getString(CRASH_MARKER_KEY_LAST_CRASH, null) ?: return null
        return runCatching {
            json
                .decodeFromString(CrashMarker.serializer(), rawValue)
                .toBoundedCrashMarker()
        }.getOrNull()
    }

    fun clear() {
        preferences.edit().remove(CRASH_MARKER_KEY_LAST_CRASH).apply()
    }
}

private fun CrashMarker.toBoundedCrashMarker(): CrashMarker =
    CrashMarker(
        timestampEpochMillis = timestampEpochMillis,
        threadName =
            threadName
                .toBoundedCrashMarkerText(CRASH_MARKER_THREAD_NAME_MAX_CHARS)
                ?: CRASH_MARKER_UNKNOWN_THREAD,
        exceptionType =
            exceptionType
                .toBoundedCrashMarkerText(CRASH_MARKER_EXCEPTION_TYPE_MAX_CHARS)
                ?: CRASH_MARKER_UNKNOWN_EXCEPTION,
        message = message.toBoundedCrashMarkerText(CRASH_MARKER_MESSAGE_MAX_CHARS),
        stackTrace = stackTrace.take(CRASH_MARKER_STACKTRACE_MAX_CHARS),
    )

private fun String?.toBoundedCrashMarkerText(maxChars: Int): String? =
    this
        ?.trim()
        ?.take(maxChars)
        ?.takeIf(String::isNotBlank)
