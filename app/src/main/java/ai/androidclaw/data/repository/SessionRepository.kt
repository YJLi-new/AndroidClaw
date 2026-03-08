package ai.androidclaw.data.repository

import ai.androidclaw.data.db.dao.SessionDao
import ai.androidclaw.data.db.entity.SessionEntity
import ai.androidclaw.data.model.Session
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SessionRepository(
    private val dao: SessionDao,
) {
    suspend fun createSession(title: String, isMain: Boolean = false): Session {
        val now = Instant.now()
        val entity = SessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            isMain = isMain,
            createdAt = now.toEpochMilli(),
            updatedAt = now.toEpochMilli(),
            archivedAt = null,
            summaryText = null,
        )
        dao.insert(entity)
        return entity.toDomain()
    }

    suspend fun getOrCreateMainSession(): Session {
        return dao.getMainSession()?.toDomain()
            ?: createSession(title = "Main session", isMain = true)
    }

    suspend fun getSession(id: String): Session? = dao.getById(id)?.toDomain()

    fun observeSessions(): Flow<List<Session>> = dao.getAllSessions().map { sessions ->
        sessions.map(SessionEntity::toDomain)
    }

    suspend fun updateTitle(id: String, title: String) {
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
                title = title,
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
}

private fun SessionEntity.toDomain(): Session {
    return Session(
        id = id,
        title = title,
        isMain = isMain,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        archived = archivedAt != null,
        summaryText = summaryText,
    )
}
