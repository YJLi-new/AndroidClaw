package ai.androidclaw.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "message_search_tokens",
    primaryKeys = ["messageId", "token"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["token"]),
        Index(value = ["sessionId", "token"]),
        Index(value = ["messageId"]),
    ],
)
data class MessageSearchTokenEntity(
    val messageId: String,
    val sessionId: String,
    val token: String,
)
