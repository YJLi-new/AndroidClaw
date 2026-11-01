package ai.androidclaw.data.repository

import ai.androidclaw.data.attachments.AttachmentStore
import ai.androidclaw.data.attachments.StagedAttachment
import ai.androidclaw.data.db.dao.MessageAttachmentDao
import ai.androidclaw.data.db.entity.MessageAttachmentEntity
import ai.androidclaw.data.model.AttachmentKind
import ai.androidclaw.data.model.MessageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock

class AttachmentRepository(
    private val dao: MessageAttachmentDao,
    val store: AttachmentStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Links imported files to a persisted message. */
    suspend fun attachToMessage(
        messageId: String,
        sessionId: String,
        staged: List<StagedAttachment>,
        kind: AttachmentKind = AttachmentKind.UserImage,
        durationMs: Long? = null,
    ): List<MessageAttachment> {
        if (staged.isEmpty()) {
            return emptyList()
        }
        val now = clock.millis()
        val entities =
            staged.mapIndexed { index, item ->
                MessageAttachmentEntity(
                    id = item.id,
                    messageId = messageId,
                    sessionId = sessionId,
                    kind = kind.storageValue,
                    mimeType = item.mimeType,
                    displayName = item.displayName,
                    relativePath = item.relativePath,
                    byteCount = item.byteCount,
                    width = item.width,
                    height = item.height,
                    durationMs = durationMs,
                    // Keeps the picked order stable when rows share a millisecond.
                    createdAt = now + index,
                )
            }
        dao.insertAll(entities)
        return entities.map(MessageAttachmentEntity::toDomain)
    }

    fun observeForSession(sessionId: String): Flow<List<MessageAttachment>> = dao.observeBySessionId(sessionId).map { rows -> rows.map(MessageAttachmentEntity::toDomain) }

    suspend fun getForMessages(messageIds: Collection<String>): Map<String, List<MessageAttachment>> {
        val ids = messageIds.distinct()
        if (ids.isEmpty()) {
            return emptyMap()
        }
        return ids
            .chunked(MESSAGE_ID_BATCH_SIZE)
            .flatMap { chunk -> dao.getByMessageIds(chunk) }
            .map(MessageAttachmentEntity::toDomain)
            .groupBy(MessageAttachment::messageId)
    }

    suspend fun getAttachment(id: String): MessageAttachment? = dao.getById(id)?.toDomain()

    fun file(relativePath: String): File = store.file(relativePath)

    fun thumbnailFile(relativePath: String): File = store.thumbnailFile(relativePath)

    /** Removes files of chips that were never sent. */
    suspend fun discardStaged(staged: List<StagedAttachment>) {
        if (staged.isEmpty()) return
        withContext(Dispatchers.IO) { staged.forEach { store.delete(it.relativePath) } }
    }

    suspend fun deleteFiles(attachments: List<MessageAttachment>) {
        if (attachments.isEmpty()) return
        withContext(Dispatchers.IO) { attachments.forEach { store.delete(it.relativePath) } }
    }

    suspend fun deleteSessionFiles(sessionId: String) {
        withContext(Dispatchers.IO) { store.deleteSession(sessionId) }
    }
}

internal fun MessageAttachmentEntity.toDomain(): MessageAttachment =
    MessageAttachment(
        id = id,
        messageId = messageId,
        sessionId = sessionId,
        kind = AttachmentKind.fromStorage(kind),
        mimeType = mimeType,
        displayName = displayName,
        relativePath = relativePath,
        byteCount = byteCount,
        width = width,
        height = height,
        durationMs = durationMs,
        createdAt = java.time.Instant.ofEpochMilli(createdAt),
    )
