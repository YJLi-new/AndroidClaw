package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.EventLogDao
import ai.androidclaw.data.db.entity.EventLogEntity
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.model.EventLogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

internal const val EVENT_LOG_MESSAGE_MAX_CHARS = 1_000
internal const val EVENT_LOG_DETAILS_MAX_CHARS = 4_000
internal const val EVENT_LOG_QUERY_MAX_LIMIT = 500

class EventLogRepository(
    private val dao: EventLogDao,
) {
    suspend fun log(
        category: EventCategory,
        level: EventLevel,
        message: String,
        details: String? = null,
    ) {
        dao.insert(
            EventLogEntity(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now().toEpochMilli(),
                category = category.toStorage(),
                level = level.toStorage(),
                message = message.toBoundedLogText(EVENT_LOG_MESSAGE_MAX_CHARS),
                detailsJson = details?.toBoundedLogText(EVENT_LOG_DETAILS_MAX_CHARS),
            ),
        )
    }

    fun observeRecent(limit: Int = 100): Flow<List<EventLogEntry>> {
        val boundedLimit = limit.coerceIn(0, EVENT_LOG_QUERY_MAX_LIMIT)
        if (boundedLimit == 0) {
            return flowOf(emptyList())
        }
        return dao.getRecent(boundedLimit).map { events ->
            events.map(EventLogEntity::toDomain)
        }
    }

    suspend fun get(id: String): EventLogEntry? {
        if (id.isBlank()) {
            return null
        }
        return dao.getById(id.trim())?.toDomain()
    }

    suspend fun delete(id: String): Int = dao.deleteById(id.trim())

    suspend fun clearAll(): Int = dao.deleteAll()

    suspend fun trimOlderThan(instant: Instant): Int = dao.deleteOlderThan(instant.toEpochMilli())

    suspend fun count(): Int = dao.count()
}

private fun EventLogEntity.toDomain(): EventLogEntry =
    EventLogEntry(
        id = id,
        timestamp = Instant.ofEpochMilli(timestamp),
        category = category.toEventCategory(),
        level = level.toEventLevel(),
        message = message.toBoundedLogText(EVENT_LOG_MESSAGE_MAX_CHARS),
        details = detailsJson?.toBoundedLogText(EVENT_LOG_DETAILS_MAX_CHARS),
    )

private fun EventCategory.toStorage(): String =
    when (this) {
        EventCategory.Provider -> "provider"
        EventCategory.Tool -> "tool"
        EventCategory.Scheduler -> "scheduler"
        EventCategory.Skill -> "skill"
        EventCategory.System -> "system"
        EventCategory.Debug -> "debug"
    }

private fun EventLevel.toStorage(): String =
    when (this) {
        EventLevel.Info -> "info"
        EventLevel.Warn -> "warn"
        EventLevel.Error -> "error"
    }

private fun String.toEventCategory(): EventCategory =
    when (this) {
        "provider" -> EventCategory.Provider
        "tool" -> EventCategory.Tool
        "scheduler" -> EventCategory.Scheduler
        "skill" -> EventCategory.Skill
        "system" -> EventCategory.System
        "debug" -> EventCategory.Debug
        else -> EventCategory.System
    }

private fun String.toEventLevel(): EventLevel =
    when (this) {
        "info" -> EventLevel.Info
        "warn" -> EventLevel.Warn
        "error" -> EventLevel.Error
        else -> EventLevel.Warn
    }

private fun String.toBoundedLogText(maxChars: Int): String {
    require(maxChars > TRUNCATED_SUFFIX.length) { "maxChars must leave room for the truncation suffix." }
    return if (length <= maxChars) {
        this
    } else {
        take(maxChars - TRUNCATED_SUFFIX.length) + TRUNCATED_SUFFIX
    }
}

private const val TRUNCATED_SUFFIX = "…[truncated]"
