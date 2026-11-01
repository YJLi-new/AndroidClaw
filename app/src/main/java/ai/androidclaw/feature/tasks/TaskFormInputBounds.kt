package ai.androidclaw.feature.tasks

internal const val TASK_FORM_INTERVAL_MINUTES_MAX_CHARS = 20
internal const val TASK_FORM_CRON_EXPRESSION_MAX_CHARS = 120

internal data class BoundedTaskFormInput(
    val value: String,
    val wasTruncated: Boolean,
)

internal fun boundTaskFormInput(
    value: String,
    maxChars: Int,
): BoundedTaskFormInput {
    require(maxChars > 0) { "Task form input max must be positive." }
    val boundedValue = value.take(maxChars)
    return BoundedTaskFormInput(
        value = boundedValue,
        wasTruncated = value.length > boundedValue.length,
    )
}
