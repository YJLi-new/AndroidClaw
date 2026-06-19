package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.MemoryItemDao
import ai.androidclaw.data.db.dao.MemorySourceTypeStatsRow
import ai.androidclaw.data.db.entity.MemoryItemEntity
import ai.androidclaw.data.db.entity.MemorySearchTokenEntity
import ai.androidclaw.data.model.MemoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.util.UUID

class MemoryRepository(
    private val dao: MemoryItemDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: Clock = Clock.systemUTC(),
) {
    data class MemoryStats(
        val totalMemoryCount: Long,
        val activeMemoryCount: Long,
        val deletedMemoryCount: Long,
        val activeWithSourceSessionCount: Long,
        val oldestActiveCreatedAt: Instant?,
        val newestActiveUpdatedAt: Instant?,
        val sourceTypeStats: List<SourceTypeStats>,
    )

    data class SourceTypeStats(
        val sourceType: String,
        val memoryCount: Long,
    )

    data class RestoredMemory(
        val memory: MemoryItem,
        val restored: Boolean,
    )

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

        val now = clock.millis()
        val entity =
            MemoryItemEntity(
                id = UUID.randomUUID().toString(),
                ownerUserId = ownerUserId,
                text = normalizedText.toBoundedMemoryText(),
                sourceSessionId = sourceSessionId?.takeIf { it.isNotBlank() },
                sourceMessageIdsJson = json.encodeToString(sourceMessageIds.toBoundedSourceMessageIds()),
                sourceType = sourceType.ifBlank { SOURCE_TYPE_MANUAL },
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        dao.insert(entity)
        replaceSearchTokens(entity)
        return entity.toDomain(json)
    }

    suspend fun search(
        ownerUserId: String,
        query: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<MemoryItem> {
        val boundedLimit = limit.coerceIn(0, MAX_SEARCH_LIMIT)
        val queryTerms = tokenizeSearchText(query)
        if (ownerUserId.isBlank() || queryTerms.isEmpty() || boundedLimit == 0) {
            return emptyList()
        }
        val normalizedQuery = normalizeForDuplicate(query)
        val tokenCandidates =
            dao.searchActiveByTokens(
                ownerUserId = ownerUserId,
                tokens = queryTerms.toList(),
                minimumMatchedTokens = minimumSearchTokenMatches(queryTerms),
                limit = SEARCH_SCAN_LIMIT,
            )
        val directCandidates =
            normalizedQuery
                .takeIf { it.length >= MIN_DATABASE_SEARCH_CHARS }
                ?.let { databaseQuery ->
                    dao.searchActiveByTextLike(
                        ownerUserId = ownerUserId,
                        escapedQuery = databaseQuery.toSqliteLikeEscapedLiteral(),
                        limit = SEARCH_SCAN_LIMIT,
                    )
                }.orEmpty()
        val recentCandidates = dao.getActiveByOwner(ownerUserId = ownerUserId, limit = SEARCH_SCAN_LIMIT)
        return (tokenCandidates + directCandidates + recentCandidates)
            .distinctBy(MemoryItemEntity::id)
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
    ): List<MemoryItem> {
        val boundedLimit = limit.coerceIn(0, MAX_LIST_LIMIT)
        if (ownerUserId.isBlank() || boundedLimit == 0) {
            return emptyList()
        }
        return dao
            .getActiveByOwner(
                ownerUserId = ownerUserId,
                limit = boundedLimit,
            ).map { it.toDomain(json) }
    }

    suspend fun listDeletedRecent(
        ownerUserId: String,
        limit: Int = DEFAULT_LIST_LIMIT,
    ): List<MemoryItem> {
        val boundedLimit = limit.coerceIn(0, MAX_LIST_LIMIT)
        if (ownerUserId.isBlank() || boundedLimit == 0) {
            return emptyList()
        }
        return dao
            .getDeletedByOwner(
                ownerUserId = ownerUserId,
                limit = boundedLimit,
            ).map { it.toDomain(json) }
    }

    suspend fun listTimeline(
        ownerUserId: String,
        includeDeleted: Boolean = false,
        limit: Int = DEFAULT_LIST_LIMIT,
    ): List<MemoryItem> {
        val boundedLimit = limit.coerceIn(0, MAX_LIST_LIMIT)
        if (ownerUserId.isBlank() || boundedLimit == 0) {
            return emptyList()
        }
        return dao
            .getTimelineByOwner(
                ownerUserId = ownerUserId,
                includeDeleted = includeDeleted,
                limit = boundedLimit,
            ).map { it.toDomain(json) }
    }

    suspend fun listForSourceSession(
        ownerUserId: String,
        sourceSessionId: String,
        limit: Int = DEFAULT_LIST_LIMIT,
    ): List<MemoryItem> {
        val boundedLimit = limit.coerceIn(0, MAX_LIST_LIMIT)
        val normalizedSourceSessionId = sourceSessionId.trim()
        if (ownerUserId.isBlank() || normalizedSourceSessionId.isBlank() || boundedLimit == 0) {
            return emptyList()
        }
        return dao
            .getActiveByOwnerAndSourceSession(
                ownerUserId = ownerUserId,
                sourceSessionId = normalizedSourceSessionId,
                limit = boundedLimit,
            ).map { it.toDomain(json) }
    }

    suspend fun listForSourceType(
        ownerUserId: String,
        sourceType: String,
        limit: Int = DEFAULT_LIST_LIMIT,
    ): List<MemoryItem> {
        val boundedLimit = limit.coerceIn(0, MAX_LIST_LIMIT)
        val normalizedSourceType = sourceType.trim()
        if (ownerUserId.isBlank() || normalizedSourceType.isBlank() || boundedLimit == 0) {
            return emptyList()
        }
        return dao
            .getActiveByOwnerAndSourceType(
                ownerUserId = ownerUserId,
                sourceType = normalizedSourceType,
                limit = boundedLimit,
            ).map { it.toDomain(json) }
    }

    suspend fun listForSourceMessage(
        ownerUserId: String,
        sourceMessageId: String,
        limit: Int = DEFAULT_LIST_LIMIT,
    ): List<MemoryItem> {
        val boundedLimit = limit.coerceIn(0, MAX_LIST_LIMIT)
        val normalizedSourceMessageId =
            listOf(sourceMessageId)
                .toBoundedSourceMessageIds()
                .firstOrNull()
        if (ownerUserId.isBlank() || normalizedSourceMessageId.isNullOrBlank() || boundedLimit == 0) {
            return emptyList()
        }
        return dao
            .getActiveByOwner(
                ownerUserId = ownerUserId,
                limit = SOURCE_MESSAGE_SCAN_LIMIT,
            ).map { it.toDomain(json) }
            .filter { memory -> normalizedSourceMessageId in memory.sourceMessageIds }
            .take(boundedLimit)
    }

    suspend fun get(
        ownerUserId: String,
        id: String,
    ): MemoryItem? {
        if (ownerUserId.isBlank() || id.isBlank()) {
            return null
        }
        return dao
            .getActiveByOwnerAndId(
                ownerUserId = ownerUserId,
                id = id,
            )?.toDomain(json)
    }

    suspend fun update(
        ownerUserId: String,
        id: String,
        text: String,
    ): MemoryItem? {
        val normalizedText = normalizeMemoryText(text)
        if (ownerUserId.isBlank() || id.isBlank() || normalizedText.isBlank()) {
            return null
        }
        val updated =
            dao.updateText(
                ownerUserId = ownerUserId,
                id = id,
                text = normalizedText.toBoundedMemoryText(),
                updatedAt = clock.millis(),
            )
        if (updated <= 0) {
            return null
        }
        val updatedMemory = dao.getActiveByOwnerAndId(ownerUserId, id) ?: return null
        replaceSearchTokens(updatedMemory)
        return updatedMemory.toDomain(json)
    }

    suspend fun countActive(ownerUserId: String): Int =
        if (ownerUserId.isBlank()) {
            0
        } else {
            dao.countActive(ownerUserId)
        }

    fun observeActiveCount(ownerUserId: String): Flow<Int> =
        if (ownerUserId.isBlank()) {
            flowOf(0)
        } else {
            dao.observeActiveCount(ownerUserId)
        }

    suspend fun stats(ownerUserId: String): MemoryStats {
        if (ownerUserId.isBlank()) {
            return MemoryStats(
                totalMemoryCount = 0,
                activeMemoryCount = 0,
                deletedMemoryCount = 0,
                activeWithSourceSessionCount = 0,
                oldestActiveCreatedAt = null,
                newestActiveUpdatedAt = null,
                sourceTypeStats = emptyList(),
            )
        }
        val stats = dao.getStatsByOwner(ownerUserId)
        return MemoryStats(
            totalMemoryCount = stats.totalMemoryCount,
            activeMemoryCount = stats.activeMemoryCount,
            deletedMemoryCount = stats.deletedMemoryCount,
            activeWithSourceSessionCount = stats.activeWithSourceSessionCount,
            oldestActiveCreatedAt = stats.oldestActiveCreatedAt?.let(Instant::ofEpochMilli),
            newestActiveUpdatedAt = stats.newestActiveUpdatedAt?.let(Instant::ofEpochMilli),
            sourceTypeStats =
                dao
                    .getActiveSourceTypeStats(ownerUserId)
                    .map(MemorySourceTypeStatsRow::toSourceTypeStats),
        )
    }

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
            deletedAt = clock.millis(),
        ) > 0
    }

    suspend fun restore(
        ownerUserId: String,
        id: String,
    ): RestoredMemory? {
        if (ownerUserId.isBlank() || id.isBlank()) {
            return null
        }
        val existing =
            dao
                .getByOwnerAndId(
                    ownerUserId = ownerUserId,
                    id = id,
                )?.toDomain(json)
                ?: return null
        if (existing.deletedAt == null) {
            return RestoredMemory(
                memory = existing,
                restored = false,
            )
        }
        val restored =
            dao.restore(
                ownerUserId = ownerUserId,
                id = id,
                updatedAt = clock.millis(),
            )
        if (restored <= 0) {
            return null
        }
        val memory = dao.getActiveByOwnerAndId(ownerUserId, id) ?: return null
        replaceSearchTokens(memory)
        return memory.toDomain(json).let { restoredMemory ->
            RestoredMemory(
                memory = restoredMemory,
                restored = true,
            )
        }
    }

    suspend fun clear(ownerUserId: String): Int {
        if (ownerUserId.isBlank()) {
            return 0
        }
        return dao.softDeleteAll(
            ownerUserId = ownerUserId,
            deletedAt = clock.millis(),
        )
    }

    suspend fun repairMissingSearchTokens(limit: Int = MEMORY_SEARCH_INDEX_REPAIR_LIMIT): Int {
        val boundedLimit = limit.coerceIn(0, SEARCH_SCAN_LIMIT)
        if (boundedLimit == 0) {
            return 0
        }
        val missing = dao.getActiveMissingSearchTokens(boundedLimit)
        missing.forEach { entity ->
            replaceSearchTokens(entity)
        }
        return missing.size
    }

    private suspend fun replaceSearchTokens(entity: MemoryItemEntity) {
        dao.deleteSearchTokensByMemoryId(entity.id)
        val tokens = entity.toSearchTokenEntities()
        if (tokens.isNotEmpty()) {
            dao.insertSearchTokens(tokens)
        }
    }

    companion object {
        const val SOURCE_TYPE_AUTOMATIC = "automatic"
        const val SOURCE_TYPE_MANUAL = "manual"
        const val DEFAULT_SEARCH_LIMIT = 5
        const val DEFAULT_LIST_LIMIT = 20
        const val MAX_SEARCH_LIMIT = 10
        const val MAX_LIST_LIMIT = 50
        const val MAX_MEMORY_TEXT_CHARS = 500
        const val MAX_SOURCE_MESSAGE_IDS = 20
        const val MAX_SOURCE_MESSAGE_ID_CHARS = 120
        private const val DUPLICATE_SCAN_LIMIT = 1_000
        private const val SEARCH_SCAN_LIMIT = 500
        private const val MEMORY_SEARCH_INDEX_REPAIR_LIMIT = 500
        private const val SOURCE_MESSAGE_SCAN_LIMIT = 1_000
        private const val MIN_DATABASE_SEARCH_CHARS = 2
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
    val boundedText = entity.text.toBoundedMemoryText()
    val normalizedText = normalizeForDuplicate(boundedText)
    if (normalizedText.isBlank()) {
        return 0
    }
    var score = 0
    if (normalizedQuery.length >= 4 && normalizedText.contains(normalizedQuery)) {
        score += 8
    }
    val memoryTerms = tokenizeSearchText(boundedText)
    queryTerms.forEach { term ->
        if (term in memoryTerms) {
            score += if (term.length == 1) 1 else 3
        } else if (term.length > 1 && normalizedText.contains(term)) {
            score += 1
        }
    }
    return score
}

internal fun normalizeMemoryText(text: String): String = normalizeSearchText(text)

private fun normalizeForDuplicate(text: String): String = normalizeMemoryText(text).lowercase()

private fun MemoryItemEntity.toDomain(json: Json): MemoryItem =
    MemoryItem(
        id = id,
        ownerUserId = ownerUserId,
        text = text.toBoundedMemoryText(),
        sourceSessionId = sourceSessionId,
        sourceMessageIds = decodeSourceMessageIds(json, sourceMessageIdsJson),
        sourceType = sourceType,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        deletedAt = deletedAt?.let(Instant::ofEpochMilli),
    )

private fun MemorySourceTypeStatsRow.toSourceTypeStats(): MemoryRepository.SourceTypeStats =
    MemoryRepository.SourceTypeStats(
        sourceType = sourceType,
        memoryCount = memoryCount,
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
    }.toBoundedSourceMessageIds()

private fun String.toBoundedMemoryText(): String = take(MemoryRepository.MAX_MEMORY_TEXT_CHARS)

private fun MemoryItemEntity.toSearchTokenEntities(): List<MemorySearchTokenEntity> =
    tokenizeSearchText(text.toBoundedMemoryText())
        .map { token ->
            MemorySearchTokenEntity(
                memoryId = id,
                ownerUserId = ownerUserId,
                token = token,
            )
        }

private fun List<String>.toBoundedSourceMessageIds(): List<String> =
    asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { it.take(MemoryRepository.MAX_SOURCE_MESSAGE_ID_CHARS) }
        .distinct()
        .take(MemoryRepository.MAX_SOURCE_MESSAGE_IDS)
        .toList()
