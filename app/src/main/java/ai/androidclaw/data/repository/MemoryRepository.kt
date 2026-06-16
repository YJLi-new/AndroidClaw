package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.MemoryItemDao
import ai.androidclaw.data.db.dao.MemorySourceTypeStatsRow
import ai.androidclaw.data.db.entity.MemoryItemEntity
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
        return entity.toDomain(json)
    }

    suspend fun search(
        ownerUserId: String,
        query: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<MemoryItem> {
        val boundedLimit = limit.coerceIn(0, MAX_SEARCH_LIMIT)
        val queryTerms = tokenize(query)
        if (ownerUserId.isBlank() || queryTerms.isEmpty() || boundedLimit == 0) {
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
        return get(ownerUserId, id)
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
        return get(ownerUserId, id)?.let { memory ->
            RestoredMemory(
                memory = memory,
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
    val memoryTerms = tokenize(boundedText)
    queryTerms.forEach { term ->
        if (term in memoryTerms) {
            score += if (term.length == 1) 1 else 3
        } else if (term.length > 1 && normalizedText.contains(term)) {
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

private fun tokenize(text: String): Set<String> {
    val normalizedText = normalizeMemoryText(text).lowercase()
    if (normalizedText.isBlank()) {
        return emptySet()
    }

    val tokens = linkedSetOf<String>()
    searchTokenRegex.findAll(normalizedText).forEach { match ->
        val rawToken = match.value.trim('_')
        if (rawToken.isBlank()) {
            return@forEach
        }
        if (rawToken.any(Char::isCompactScriptSearchChar)) {
            val compactChars = rawToken.filter(Char::isCompactScriptSearchChar)
            compactChars.forEach { tokens += it.toString() }
            compactChars.windowed(size = 2).forEach { tokens += it }
            rawToken
                .split(Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]+"))
                .map(String::trim)
                .filter { it.length >= 2 }
                .forEach { tokens += it }
        } else if (rawToken.length >= 2) {
            tokens += rawToken
        }
    }
    return tokens
}

private val searchTokenRegex = Regex("[\\p{L}\\p{N}_]+")

private fun Char.isCompactScriptSearchChar(): Boolean {
    val block = Character.UnicodeBlock.of(this)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
        block == Character.UnicodeBlock.HANGUL_JAMO ||
        block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
}

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

private fun List<String>.toBoundedSourceMessageIds(): List<String> =
    asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { it.take(MemoryRepository.MAX_SOURCE_MESSAGE_ID_CHARS) }
        .distinct()
        .take(MemoryRepository.MAX_SOURCE_MESSAGE_IDS)
        .toList()
