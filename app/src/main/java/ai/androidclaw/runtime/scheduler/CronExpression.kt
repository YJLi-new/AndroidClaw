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
        val dowValue =
            when (dateTime.dayOfWeek) {
                DayOfWeek.SUNDAY -> 0
                else -> dateTime.dayOfWeek.value
            }
        val dowMatches = dayOfWeek.matches(dowValue)
        val dayMatches =
            when {
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
            val expanded =
                when (input.trim()) {
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
                    allowed =
                        (minimum..maximum)
                            .map { if (normalizeSevenToZero && it == 7) 0 else it }
                            .toSet(),
                    isWildcard = true,
                )
            }

            require(normalized.isNotBlank()) { "Cron field cannot be empty." }
            val parts = normalized.split(',')
            require(parts.all { it.isNotBlank() }) {
                "Cron field cannot contain empty list items."
            }

            val values =
                parts
                    .flatMap { parsePart(it.trim(), minimum, maximum, normalizeSevenToZero) }
                    .toSet()

            require(values.isNotEmpty()) { "Cron field cannot be empty." }
            return CronField(allowed = values, isWildcard = false)
        }

        private fun parseNumber(
            input: String,
            label: String,
        ): Int =
            input.toIntOrNull()
                ?: throw IllegalArgumentException(
                    "Invalid cron $label: '${input.toDisplayToken()}'.",
                )

        private fun String.toDisplayToken(): String {
            val collapsed = trim().replace(Regex("\\s+"), " ")
            return if (collapsed.length <= MAX_ERROR_TOKEN_LENGTH) {
                collapsed
            } else {
                collapsed.take(MAX_ERROR_TOKEN_LENGTH) + "…"
            }
        }

        private const val MAX_ERROR_TOKEN_LENGTH = 40

        private fun parsePart(
            input: String,
            minimum: Int,
            maximum: Int,
            normalizeSevenToZero: Boolean,
        ): List<Int> {
            val slashCount = input.count { it == '/' }
            require(slashCount <= 1) { "Cron part cannot contain multiple step separators." }

            val slashIndex = input.indexOf('/')
            val base = if (slashIndex == -1) input else input.substring(0, slashIndex)
            val step =
                if (slashIndex == -1) {
                    1
                } else {
                    val stepRaw = input.substring(slashIndex + 1)
                    parseNumber(stepRaw, "step")
                }
            require(step > 0) { "Step must be > 0." }

            val seed =
                when {
                    base == "*" -> (minimum..maximum).toList()
                    '-' in base -> {
                        val (startRaw, endRaw) = base.split('-', limit = 2)
                        val start = parseNumber(startRaw, "range start")
                        val end = parseNumber(endRaw, "range end")
                        require(start <= end) { "Range start must be <= end." }
                        (start..end).toList()
                    }
                    base.isNotBlank() -> listOf(parseNumber(base, "value"))
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
