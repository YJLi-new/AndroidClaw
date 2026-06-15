package ai.androidclaw.data.model

import ai.androidclaw.runtime.scheduler.CronExpression
import ai.androidclaw.runtime.scheduler.CronField
import ai.androidclaw.runtime.scheduler.TaskSchedule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

object ScheduleSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun toJson(schedule: TaskSchedule): String = json.encodeToString(SerializedSchedule.serializer(), schedule.toSerialized())

    fun fromJson(raw: String): TaskSchedule = json.decodeFromString(SerializedSchedule.serializer(), raw).toDomain()

    fun fromJsonOrNull(raw: String): TaskSchedule? =
        try {
            fromJson(raw)
        } catch (_: IllegalArgumentException) {
            null
        }

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
                    intervalMillis = requirePositiveIntervalMillis(repeatEvery.toMillis()),
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
                    at = Instant.ofEpochMilli(requireField(atEpochMillis, "atEpochMillis")),
                )

            "interval" ->
                TaskSchedule.Interval(
                    anchorAt = Instant.ofEpochMilli(requireField(anchorAtEpochMillis, "anchorAtEpochMillis")),
                    repeatEvery =
                        Duration.ofMillis(
                            requirePositiveIntervalMillis(
                                requireField(intervalMillis, "intervalMillis"),
                            ),
                        ),
                )

            "cron" ->
                TaskSchedule.Cron(
                    expression = CronExpression.parse(requireField(cronExpr, "cronExpr")),
                    zoneId = parseZoneId(requireField(zoneId, "zoneId")),
                )

            else -> throw IllegalArgumentException("Unsupported schedule kind: $kind")
        }

    private fun requirePositiveIntervalMillis(intervalMillis: Long): Long {
        require(intervalMillis > 0L) { "Interval schedule requires a positive intervalMillis." }
        return intervalMillis
    }

    private fun <T : Any> requireField(
        value: T?,
        name: String,
    ): T = value ?: throw IllegalArgumentException("Schedule field '$name' is required.")

    private fun parseZoneId(raw: String): ZoneId {
        require(raw.isNotBlank()) { "Schedule field 'zoneId' cannot be blank." }
        return try {
            ZoneId.of(raw)
        } catch (exception: DateTimeException) {
            throw IllegalArgumentException("Invalid schedule zoneId: ${raw.toDisplayToken()}.", exception)
        }
    }

    private fun String.toDisplayToken(): String {
        val collapsed = trim().replace(Regex("\\s+"), " ")
        return if (collapsed.length <= MAX_ERROR_TOKEN_LENGTH) {
            collapsed
        } else {
            collapsed.take(MAX_ERROR_TOKEN_LENGTH) + "…"
        }
    }

    private const val MAX_ERROR_TOKEN_LENGTH = 40

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
