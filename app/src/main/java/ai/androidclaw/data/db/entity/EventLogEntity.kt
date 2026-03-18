package ai.androidclaw.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "event_logs",
    indices = [
        Index(value = ["timestamp"]),
    ],
)
data class EventLogEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val category: String,
    val level: String,
    val message: String,
    val detailsJson: String?,
)
