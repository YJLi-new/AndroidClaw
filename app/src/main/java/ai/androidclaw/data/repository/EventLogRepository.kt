package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.EventLogDao
import ai.androidclaw.data.db.entity.EventLogEntity
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.model.EventLogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

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
                message = message,
                detailsJson = details,
            ),
        )
    }

    fun observeRecent(limit: Int = 100): Flow<List<EventLogEntry>> =
        dao.getRecent(limit).map { events ->
            events.map(EventLogEntity::toDomain)
        }

    suspend fun trimOlderThan(instant: Instant): Int = dao.deleteOlderThan(instant.toEpochMilli())

    suspend fun count(): Int = dao.count()
}

private fun EventLogEntity.toDomain(): EventLogEntry =
    EventLogEntry(
        id = id,
        timestamp = Instant.ofEpochMilli(timestamp),
        category = category.toEventCategory(),
        level = level.toEventLevel(),
        message = message,
        details = detailsJson,
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
        else -> error("Unsupported event category: $this")
    }

private fun String.toEventLevel(): EventLevel =
    when (this) {
        "info" -> EventLevel.Info
        "warn" -> EventLevel.Warn
        "error" -> EventLevel.Error
        else -> error("Unsupported event level: $this")
    }
