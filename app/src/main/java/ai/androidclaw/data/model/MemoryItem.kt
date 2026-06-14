package ai.androidclaw.data.model

import java.time.Instant

data class MemoryItem(
    val id: String,
    val ownerUserId: String,
    val text: String,
    val sourceSessionId: String?,
    val sourceMessageIds: List<String>,
    val sourceType: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)
