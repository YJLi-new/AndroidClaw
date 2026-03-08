package ai.androidclaw.data.model

import java.time.Instant

data class Session(
    val id: String,
    val title: String,
    val isMain: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archived: Boolean,
    val summaryText: String?,
)
