package ai.androidclaw.runtime.scheduler

import java.time.DayOfWeek
import java.time.ZonedDateTime

data class CronExpression(
    val minute: CronField,
    val hour: CronField,
    val dayOfMonth: CronField,
    val month: CronField,
    val dayOfWeek: CronField,
) {
    fun matches(dateTime: ZonedDateTime): Boolean {
        val domMatches = dayOfMonth.matches(dateTime.dayOfMonth)
        val dowValue = when (dateTime.dayOfWeek) {
            DayOfWeek.SUNDAY -> 0
            else -> dateTime.dayOfWeek.value
        }
        val dowMatches = dayOfWeek.matches(dowValue)
        val dayMatches = when {
            dayOfMonth.isWildcard && dayOfWeek.isWildcard -> true
            dayOfMonth.isWildcard -> dowMatches
            dayOfWeek.isWildcard -> domMatches
            else -> domMatches || dowMatches
        }
        return minute.matches(dateTime.minute) &&
            hour.matches(dateTime.hour) &&
            month.matches(dateTime.monthValue) &&
            dayMatches
    }

    companion object {
        fun parse(input: String): CronExpression {
            val expanded = when (input.trim()) {
                "@hourly" -> "0 * * * *"
                "@daily" -> "0 0 * * *"
                "@weekly" -> "0 0 * * 0"
                "@monthly" -> "0 0 1 * *"
                else -> input.trim()
            }
            val parts = expanded.split(Regex("\\s+"))
            require(parts.size == 5) { "Cron expression must contain 5 fields." }
            return CronExpression(
                minute = CronField.parse(parts[0], 0, 59),
                hour = CronField.parse(parts[1], 0, 23),
                dayOfMonth = CronField.parse(parts[2], 1, 31),
                month = CronField.parse(parts[3], 1, 12),
                dayOfWeek = CronField.parse(parts[4], 0, 7, normalizeSevenToZero = true),
            )
        }
    }
}

data class CronField(
    val allowed: Set<Int>,
    val isWildcard: Boolean,
) {
    fun matches(value: Int): Boolean = allowed.contains(value)

    companion object {
        fun parse(
            input: String,
            minimum: Int,
            maximum: Int,
            normalizeSevenToZero: Boolean = false,
        ): CronField {
            val normalized = input.trim()
            if (normalized == "*") {
                return CronField(
                    allowed = (minimum..maximum)
                        .map { if (normalizeSevenToZero && it == 7) 0 else it }
                        .toSet(),
                    isWildcard = true,
                )
            }

            val values = normalized
                .split(',')
                .flatMap { parsePart(it.trim(), minimum, maximum, normalizeSevenToZero) }
                .toSet()

            require(values.isNotEmpty()) { "Cron field cannot be empty." }
            return CronField(allowed = values, isWildcard = false)
        }

        private fun parsePart(
            input: String,
            minimum: Int,
            maximum: Int,
            normalizeSevenToZero: Boolean,
        ): List<Int> {
            val slashIndex = input.indexOf('/')
            val base = if (slashIndex == -1) input else input.substring(0, slashIndex)
            val step = if (slashIndex == -1) 1 else input.substring(slashIndex + 1).toInt()
            require(step > 0) { "Step must be > 0." }

            val seed = when {
                base == "*" -> (minimum..maximum).toList()
                '-' in base -> {
                    val (startRaw, endRaw) = base.split('-', limit = 2)
                    val start = startRaw.toInt()
                    val end = endRaw.toInt()
                    require(start <= end) { "Range start must be <= end." }
                    (start..end).toList()
                }
                base.isNotBlank() -> listOf(base.toInt())
                else -> emptyList()
            }

            return seed
                .filterIndexed { index, _ -> index % step == 0 }
                .map { value ->
                    require(value in minimum..maximum) {
                        "Value $value out of range $minimum..$maximum."
                    }
                    if (normalizeSevenToZero && value == 7) 0 else value
                }
        }
    }
}

