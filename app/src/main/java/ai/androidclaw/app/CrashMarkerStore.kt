package ai.androidclaw.app

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

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
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun record(
        threadName: String,
        throwable: Throwable,
        timestamp: Instant = Instant.now(),
    ) {
        val marker =
            CrashMarker(
                timestampEpochMillis = timestamp.toEpochMilli(),
                threadName = threadName,
                exceptionType = throwable::class.java.name,
                message = throwable.message,
                stackTrace = throwable.stackTraceToString().take(MAX_STACKTRACE_CHARS),
            )
        preferences
            .edit()
            .putString(KEY_LAST_CRASH, json.encodeToString(CrashMarker.serializer(), marker))
            .commit()
    }

    fun read(): CrashMarker? {
        val rawValue = preferences.getString(KEY_LAST_CRASH, null) ?: return null
        return runCatching {
            json.decodeFromString(CrashMarker.serializer(), rawValue)
        }.getOrNull()
    }

    fun clear() {
        preferences.edit().remove(KEY_LAST_CRASH).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "androidclaw_crash_markers"
        const val KEY_LAST_CRASH = "last_crash"
        const val MAX_STACKTRACE_CHARS = 8_000
    }
}
