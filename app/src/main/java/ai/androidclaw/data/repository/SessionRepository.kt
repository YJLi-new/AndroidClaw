package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.SessionActivityRow
import ai.androidclaw.data.db.dao.SessionDao
import ai.androidclaw.data.db.dao.SessionStatsRow
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

internal const val SESSION_TITLE_MAX_CHARS = 160
internal const val SESSION_SUMMARY_MAX_CHARS = 4_000
internal const val SESSION_COMPACTION_BOUNDARY_ID_MAX_CHARS = 256
internal const val SESSION_SEARCH_MAX_LIMIT = 200

class SessionRepository(
    private val dao: SessionDao,
) {
    private val mainSessionMutex = Mutex()

    data class SearchResult(
        val sessionId: String,
        val sessionTitle: String,
    )

    data class SessionStats(
        val totalSessionCount: Long,
        val activeSessionCount: Long,
        val archivedSessionCount: Long,
        val mainSessionCount: Long,
        val summarizedSessionCount: Long,
        val compactedSessionCount: Long,
        val oldestSessionCreatedAt: Instant?,
        val newestSessionUpdatedAt: Instant?,
        val newestArchivedAt: Instant?,
    )

    data class SessionActivity(
        val session: Session,
        val messageCount: Long,
        val latestMessageId: String?,
        val latestMessageRole: MessageRole?,
        val latestMessageContent: String?,
        val latestMessageCreatedAt: Instant?,
    )

    suspend fun createSession(
        title: String,
        isMain: Boolean = false,
    ): Session {
        val now = Instant.now()
        val entity =
            SessionEntity(
                id = UUID.randomUUID().toString(),
                title = title.toBoundedSessionText(SESSION_TITLE_MAX_CHARS),
                isMain = isMain,
                createdAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
                archivedAt = null,
                summaryText = null,
                compactedUntilMessageId = null,
            )
        dao.insert(entity)
        return entity.toDomain()
    }

    suspend fun getOrCreateMainSession(): Session =
        mainSessionMutex.withLock {
            dao.getMainSession()?.toDomain()
                ?: createSession(title = "Main session", isMain = true)
        }

    suspend fun getSession(id: String): Session? = dao.getById(id)?.toDomain()

    suspend fun deleteSession(id: String): Boolean = dao.deleteById(id) > 0

    suspend fun getSessionsWithCompactionBoundary(): List<Session> =
        dao
            .getSessionsWithCompactionBoundary()
            .map(SessionEntity::toDomain)
            .filter { session -> session.compactedUntilMessageId != null }

    fun observeSessions(): Flow<List<Session>> =
        dao.getAllSessions().map { sessions ->
            sessions.map(SessionEntity::toDomain)
        }

    fun observeArchivedSessions(): Flow<List<Session>> =
        dao.getArchivedSessions().map { sessions ->
            sessions.map(SessionEntity::toDomain)
        }

    suspend fun updateTitle(
        id: String,
        title: String,
    ) {
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                title = title.toBoundedSessionText(SESSION_TITLE_MAX_CHARS),
                updatedAt = Instant.now().toEpochMilli(),
            ),
        )
    }

    suspend fun archiveSession(id: String) {
        val existing = dao.getById(id) ?: return
        val now = Instant.now().toEpochMilli()
        dao.update(
            existing.copy(
                updatedAt = now,
                archivedAt = now,
            ),
        )
    }

    suspend fun unarchiveSession(id: String) {
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                updatedAt = Instant.now().toEpochMilli(),
                archivedAt = null,
            ),
        )
    }

    suspend fun updateSummary(
        id: String,
        summaryText: String?,
    ) {
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                summaryText = summaryText?.toBoundedSessionText(SESSION_SUMMARY_MAX_CHARS),
                updatedAt = Instant.now().toEpochMilli(),
            ),
        )
    }

    suspend fun updateSummaryAndCompactionBoundary(
        id: String,
        summaryText: String,
        compactedUntilMessageId: String,
    ) {
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                summaryText = summaryText.toBoundedSessionText(SESSION_SUMMARY_MAX_CHARS),
                compactedUntilMessageId = compactedUntilMessageId.toBoundedCompactionBoundaryIdOrNull(),
                updatedAt = Instant.now().toEpochMilli(),
            ),
        )
    }

    suspend fun clearCompactionBoundary(id: String) {
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                compactedUntilMessageId = null,
                updatedAt = Instant.now().toEpochMilli(),
            ),
        )
    }

    suspend fun updateSummaryState(
        id: String,
        summaryText: String?,
        compactedUntilMessageId: String?,
    ) {
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                summaryText = summaryText?.toBoundedSessionText(SESSION_SUMMARY_MAX_CHARS),
                compactedUntilMessageId = compactedUntilMessageId?.toBoundedCompactionBoundaryIdOrNull(),
                updatedAt = Instant.now().toEpochMilli(),
            ),
        )
    }

    suspend fun searchSessions(
        query: String,
        limit: Int,
    ): List<SearchResult> {
        val queryPattern = query.toSqlLikeContainsPatternOrNull()
        val boundedLimit = limit.coerceIn(0, SESSION_SEARCH_MAX_LIMIT)
        if (queryPattern == null || boundedLimit == 0) {
            return emptyList()
        }
        return dao.searchByTitle(queryPattern, boundedLimit).map { session ->
            SearchResult(
                sessionId = session.id,
                sessionTitle = session.title.toBoundedSessionText(SESSION_TITLE_MAX_CHARS),
            )
        }
    }

    suspend fun listSummarizedSessions(
        limit: Int,
        includeArchived: Boolean = false,
    ): List<Session> {
        val boundedLimit = limit.coerceIn(0, SESSION_SEARCH_MAX_LIMIT)
        if (boundedLimit == 0) {
            return emptyList()
        }
        return dao
            .getSummarizedSessions(
                includeArchived = includeArchived,
                limit = boundedLimit,
            ).map(SessionEntity::toDomain)
    }

    suspend fun listSessionActivity(
        limit: Int,
        includeArchived: Boolean = false,
    ): List<SessionActivity> {
        val boundedLimit = limit.coerceIn(0, SESSION_SEARCH_MAX_LIMIT)
        if (boundedLimit == 0) {
            return emptyList()
        }
        return dao
            .getActivity(
                includeArchived = includeArchived,
                limit = boundedLimit,
            ).map(SessionActivityRow::toSessionActivity)
    }

    suspend fun getSessionStats(): SessionStats = dao.getStats().toSessionStats()
}

private fun String.toBoundedSessionText(maxChars: Int): String =
    if (length <= maxChars) {
        this
    } else {
        take(maxChars)
    }

private fun SessionEntity.toDomain(): Session =
    Session(
        id = id,
        title = title.toBoundedSessionText(SESSION_TITLE_MAX_CHARS),
        isMain = isMain,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        archived = archivedAt != null,
        summaryText = summaryText?.toBoundedSessionText(SESSION_SUMMARY_MAX_CHARS),
        compactedUntilMessageId = compactedUntilMessageId?.toBoundedCompactionBoundaryIdOrNull(),
    )

private fun SessionStatsRow.toSessionStats(): SessionRepository.SessionStats =
    SessionRepository.SessionStats(
        totalSessionCount = totalSessionCount,
        activeSessionCount = activeSessionCount,
        archivedSessionCount = archivedSessionCount,
        mainSessionCount = mainSessionCount,
        summarizedSessionCount = summarizedSessionCount,
        compactedSessionCount = compactedSessionCount,
        oldestSessionCreatedAt = oldestSessionCreatedAt?.let(Instant::ofEpochMilli),
        newestSessionUpdatedAt = newestSessionUpdatedAt?.let(Instant::ofEpochMilli),
        newestArchivedAt = newestArchivedAt?.let(Instant::ofEpochMilli),
    )

private fun SessionActivityRow.toSessionActivity(): SessionRepository.SessionActivity =
    SessionRepository.SessionActivity(
        session =
            SessionEntity(
                id = id,
                title = title,
                isMain = isMain,
                createdAt = createdAt,
                updatedAt = updatedAt,
                archivedAt = archivedAt,
                summaryText = summaryText,
                compactedUntilMessageId = compactedUntilMessageId,
            ).toDomain(),
        messageCount = messageCount,
        latestMessageId = latestMessageId,
        latestMessageRole = latestMessageRole?.toMessageRole(),
        latestMessageContent = latestMessageContent?.take(MESSAGE_CONTENT_MAX_CHARS),
        latestMessageCreatedAt = latestMessageCreatedAt?.let(Instant::ofEpochMilli),
    )

private fun String.toMessageRole(): MessageRole =
    when (this) {
        "user" -> MessageRole.User
        "assistant" -> MessageRole.Assistant
        "tool_call" -> MessageRole.ToolCall
        "tool_result" -> MessageRole.ToolResult
        "system" -> MessageRole.System
        else -> MessageRole.System
    }

private fun String.toBoundedCompactionBoundaryIdOrNull(): String? =
    trim()
        .take(SESSION_COMPACTION_BOUNDARY_ID_MAX_CHARS)
        .takeIf(String::isNotBlank)
