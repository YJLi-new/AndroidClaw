package ai.androidclaw.data.model

import java.time.Instant

enum class EventCategory {
    Provider,
    Tool,
    Scheduler,
    Skill,
    System,
    Debug,
}

enum class EventLevel {
    Info,
    Warn,
    Error,
}

data class EventLogEntry(
    val id: String,
    val timestamp: Instant,
    val category: EventCategory,
    val level: EventLevel,
    val message: String,
    val details: String?,
)
