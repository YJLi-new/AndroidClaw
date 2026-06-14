package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.MemoryItemDao
import ai.androidclaw.data.db.entity.MemoryItemEntity
import ai.androidclaw.data.model.MemoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class MemoryRepository(
    private val dao: MemoryItemDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun remember(
        ownerUserId: String,
        text: String,
        sourceSessionId: String? = null,
        sourceMessageIds: List<String> = emptyList(),
        sourceType: String = SOURCE_TYPE_MANUAL,
    ): MemoryItem? {
        val normalizedText = normalizeMemoryText(text)
        if (ownerUserId.isBlank() || normalizedText.isBlank()) {
            return null
        }
        val existing =
            dao
                .getActiveByOwner(ownerUserId = ownerUserId, limit = DUPLICATE_SCAN_LIMIT)
                .firstOrNull { normalizeForDuplicate(it.text) == normalizeForDuplicate(normalizedText) }
        if (existing != null) {
            return existing.toDomain(json)
        }

        val now = Instant.now().toEpochMilli()
        val entity =
            MemoryItemEntity(
                id = UUID.randomUUID().toString(),
                ownerUserId = ownerUserId,
                text = normalizedText.take(MAX_MEMORY_TEXT_CHARS),
                sourceSessionId = sourceSessionId?.takeIf { it.isNotBlank() },
                sourceMessageIdsJson = json.encodeToString(sourceMessageIds.filter(String::isNotBlank).distinct()),
                sourceType = sourceType.ifBlank { SOURCE_TYPE_MANUAL },
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        dao.insert(entity)
        return entity.toDomain(json)
    }

    suspend fun search(
        ownerUserId: String,
        query: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<MemoryItem> {
        val boundedLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val queryTerms = tokenize(query)
        if (ownerUserId.isBlank() || queryTerms.isEmpty()) {
            return emptyList()
        }
        val normalizedQuery = normalizeForDuplicate(query)
        return dao
            .getActiveByOwner(ownerUserId = ownerUserId, limit = SEARCH_SCAN_LIMIT)
            .mapNotNull { entity ->
                val score = scoreMemory(normalizedQuery, queryTerms, entity)
                if (score <= 0) {
                    null
                } else {
                    ScoredMemory(entity.toDomain(json), score)
                }
            }.sortedWith(
                compareByDescending<ScoredMemory> { it.score }
                    .thenByDescending { it.memory.createdAt },
            ).take(boundedLimit)
            .map(ScoredMemory::memory)
    }

    suspend fun listRecent(
        ownerUserId: String,
        limit: Int = DEFAULT_LIST_LIMIT,
    ): List<MemoryItem> =
        dao
            .getActiveByOwner(
                ownerUserId = ownerUserId,
                limit = limit.coerceIn(1, MAX_LIST_LIMIT),
            ).map { it.toDomain(json) }

    suspend fun countActive(ownerUserId: String): Int =
        if (ownerUserId.isBlank()) {
            0
        } else {
            dao.countActive(ownerUserId)
        }

    fun observeActiveCount(ownerUserId: String): Flow<Int> = dao.observeActiveCount(ownerUserId)

    suspend fun delete(
        ownerUserId: String,
        id: String,
    ): Boolean {
        if (ownerUserId.isBlank() || id.isBlank()) {
            return false
        }
        return dao.softDelete(
            ownerUserId = ownerUserId,
            id = id,
            deletedAt = Instant.now().toEpochMilli(),
        ) > 0
    }

    suspend fun clear(ownerUserId: String): Int {
        if (ownerUserId.isBlank()) {
            return 0
        }
        return dao.softDeleteAll(
            ownerUserId = ownerUserId,
            deletedAt = Instant.now().toEpochMilli(),
        )
    }

    companion object {
        const val SOURCE_TYPE_AUTOMATIC = "automatic"
        const val SOURCE_TYPE_MANUAL = "manual"
        const val DEFAULT_SEARCH_LIMIT = 5
        const val DEFAULT_LIST_LIMIT = 20
        const val MAX_SEARCH_LIMIT = 10
        const val MAX_LIST_LIMIT = 50
        const val MAX_MEMORY_TEXT_CHARS = 500
        private const val DUPLICATE_SCAN_LIMIT = 1_000
        private const val SEARCH_SCAN_LIMIT = 500
    }
}

private data class ScoredMemory(
    val memory: MemoryItem,
    val score: Int,
)

private fun scoreMemory(
    normalizedQuery: String,
    queryTerms: Set<String>,
    entity: MemoryItemEntity,
): Int {
    val normalizedText = normalizeForDuplicate(entity.text)
    if (normalizedText.isBlank()) {
        return 0
    }
    var score = 0
    if (normalizedQuery.length >= 4 && normalizedText.contains(normalizedQuery)) {
        score += 8
    }
    val memoryTerms = tokenize(entity.text)
    queryTerms.forEach { term ->
        if (term in memoryTerms) {
            score += 3
        } else if (normalizedText.contains(term)) {
            score += 1
        }
    }
    return score
}

internal fun normalizeMemoryText(text: String): String =
    text
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(separator = " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun normalizeForDuplicate(text: String): String = normalizeMemoryText(text).lowercase()

private fun tokenize(text: String): Set<String> =
    text
        .lowercase()
        .split(Regex("[^a-z0-9_]+"))
        .map(String::trim)
        .filter { it.length >= 2 }
        .toSet()

private fun MemoryItemEntity.toDomain(json: Json): MemoryItem =
    MemoryItem(
        id = id,
        ownerUserId = ownerUserId,
        text = text,
        sourceSessionId = sourceSessionId,
        sourceMessageIds = decodeSourceMessageIds(json, sourceMessageIdsJson),
        sourceType = sourceType,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        deletedAt = deletedAt?.let(Instant::ofEpochMilli),
    )

private fun decodeSourceMessageIds(
    json: Json,
    rawValue: String,
): List<String> =
    try {
        json.decodeFromString<List<String>>(rawValue)
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
