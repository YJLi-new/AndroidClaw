package ai.androidclaw.data.model

import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.CronField
import ai.androidclaw.runtime.scheduler.TaskSchedule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

object ScheduleSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun toJson(schedule: TaskSchedule): String = json.encodeToString(SerializedSchedule.serializer(), schedule.toSerialized())

    fun fromJson(raw: String): TaskSchedule = json.decodeFromString(SerializedSchedule.serializer(), raw).toDomain()

    fun kindOf(schedule: TaskSchedule): String =
        when (schedule) {
            is TaskSchedule.Once -> "once"
            is TaskSchedule.Interval -> "interval"
            is TaskSchedule.Cron -> "cron"
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

    private fun TaskSchedule.toSerialized(): SerializedSchedule =
        when (this) {
            is TaskSchedule.Once ->
                SerializedSchedule(
                    kind = "once",
                    atEpochMillis = at.toEpochMilli(),
                )

            is TaskSchedule.Interval ->
                SerializedSchedule(
                    kind = "interval",
                    anchorAtEpochMillis = anchorAt.toEpochMilli(),
                    intervalMillis = repeatEvery.toMillis(),
                )

            is TaskSchedule.Cron ->
                SerializedSchedule(
                    kind = "cron",
                    cronExpr = expression.toSpec(),
                    zoneId = zoneId.id,
                )
        }

    private fun SerializedSchedule.toDomain(): TaskSchedule =
        when (kind) {
            "once" ->
                TaskSchedule.Once(
                    at = Instant.ofEpochMilli(requireNotNull(atEpochMillis)),
                )

            "interval" ->
                TaskSchedule.Interval(
                    anchorAt = Instant.ofEpochMilli(requireNotNull(anchorAtEpochMillis)),
                    repeatEvery = Duration.ofMillis(requireNotNull(intervalMillis)),
                )

            "cron" ->
                TaskSchedule.Cron(
                    expression = CronExpression.parse(requireNotNull(cronExpr)),
                    zoneId = ZoneId.of(requireNotNull(zoneId)),
                )

            else -> error("Unsupported schedule kind: $kind")
        }

    private fun CronExpression.toSpec(): String =
        listOf(
            minute.toSpec(),
            hour.toSpec(),
            dayOfMonth.toSpec(),
            month.toSpec(),
            dayOfWeek.toSpec(),
        ).joinToString(" ")

    private fun CronField.toSpec(): String = if (isWildcard) "*" else allowed.toList().sorted().joinToString(",")
}
