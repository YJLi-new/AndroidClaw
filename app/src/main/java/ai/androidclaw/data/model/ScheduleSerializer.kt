package ai.androidclaw.data.model

import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.CronField
import ai.androidclaw.runtime.scheduler.TaskSchedule
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ScheduleSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun toJson(schedule: TaskSchedule): String {
        return json.encodeToString(SerializedSchedule.serializer(), schedule.toSerialized())
    }

    fun fromJson(raw: String): TaskSchedule {
        return json.decodeFromString(SerializedSchedule.serializer(), raw).toDomain()
    }

    fun kindOf(schedule: TaskSchedule): String {
        return when (schedule) {
            is TaskSchedule.Once -> "once"
            is TaskSchedule.Interval -> "interval"
            is TaskSchedule.Cron -> "cron"
        }
    }

    @Serializable
    private data class SerializedSchedule(
        val kind: String,
        val atEpochMillis: Long? = null,
        val anchorAtEpochMillis: Long? = null,
        val intervalMillis: Long? = null,
        val cronExpr: String? = null,
        val zoneId: String? = null,
    )

    private fun TaskSchedule.toSerialized(): SerializedSchedule {
        return when (this) {
            is TaskSchedule.Once -> SerializedSchedule(
                kind = "once",
                atEpochMillis = at.toEpochMilli(),
            )

            is TaskSchedule.Interval -> SerializedSchedule(
                kind = "interval",
                anchorAtEpochMillis = anchorAt.toEpochMilli(),
                intervalMillis = repeatEvery.toMillis(),
            )

            is TaskSchedule.Cron -> SerializedSchedule(
                kind = "cron",
                cronExpr = expression.toSpec(),
                zoneId = zoneId.id,
            )
        }
    }

    private fun SerializedSchedule.toDomain(): TaskSchedule {
        return when (kind) {
            "once" -> TaskSchedule.Once(
                at = Instant.ofEpochMilli(requireNotNull(atEpochMillis)),
            )

            "interval" -> TaskSchedule.Interval(
                anchorAt = Instant.ofEpochMilli(requireNotNull(anchorAtEpochMillis)),
                repeatEvery = Duration.ofMillis(requireNotNull(intervalMillis)),
            )

            "cron" -> TaskSchedule.Cron(
                expression = CronExpression.parse(requireNotNull(cronExpr)),
                zoneId = ZoneId.of(requireNotNull(zoneId)),
            )

            else -> error("Unsupported schedule kind: $kind")
        }
    }

    private fun CronExpression.toSpec(): String {
        return listOf(
            minute.toSpec(),
            hour.toSpec(),
            dayOfMonth.toSpec(),
            month.toSpec(),
            dayOfWeek.toSpec(),
        ).joinToString(" ")
    }

    private fun CronField.toSpec(): String {
        return if (isWildcard) "*" else allowed.toList().sorted().joinToString(",")
    }
}
