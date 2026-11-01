package ai.androidclaw.data.model

import java.time.Instant

enum class AttachmentKind(
    val storageValue: String,
) {
    UserImage("USER_IMAGE"),
    GeneratedImage("GENERATED_IMAGE"),
    Video("VIDEO"),
    ;

    companion object {
        fun fromStorage(value: String): AttachmentKind = entries.firstOrNull { it.storageValue == value } ?: UserImage
    }
}

/** A file stored under `filesDir/attachments/<sessionId>/` and linked to one message. */
data class MessageAttachment(
    val id: String,
    val messageId: String,
    val sessionId: String,
    val kind: AttachmentKind,
    val mimeType: String,
    val displayName: String,
    val relativePath: String,
    val byteCount: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long? = null,
    val createdAt: Instant,
)
