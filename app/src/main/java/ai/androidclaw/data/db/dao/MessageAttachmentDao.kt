package ai.androidclaw.data.db.dao

import ai.androidclaw.data.db.entity.MessageAttachmentEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageAttachmentDao {
    @Insert
    suspend fun insertAll(attachments: List<MessageAttachmentEntity>)

    @Query("SELECT * FROM message_attachments WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeBySessionId(sessionId: String): Flow<List<MessageAttachmentEntity>>

    @Query("SELECT * FROM message_attachments WHERE messageId IN (:messageIds) ORDER BY createdAt ASC, id ASC")
    suspend fun getByMessageIds(messageIds: List<String>): List<MessageAttachmentEntity>

    @Query("SELECT * FROM message_attachments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageAttachmentEntity?
}
