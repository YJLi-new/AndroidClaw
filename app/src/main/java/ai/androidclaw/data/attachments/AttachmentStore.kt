package ai.androidclaw.data.attachments

import java.io.File

/**
 * Attachment files live in `filesDir/attachments/<sessionId>/`: `<id>.jpg` plus a 256 px
 * `<id>.thumb.jpg`. Paths stored in Room are relative to [root]. Nothing scans this folder at
 * startup; session and message deletes remove their files directly.
 */
class AttachmentStore(
    val root: File,
) {
    fun sessionDirectory(sessionId: String): File = File(root, sessionId.toSafeSegment())

    fun relativePathFor(
        sessionId: String,
        attachmentId: String,
        extension: String = "jpg",
    ): String = "${sessionId.toSafeSegment()}/${attachmentId.toSafeSegment()}.$extension"

    fun file(relativePath: String): File = File(root, relativePath)

    fun thumbnailFile(relativePath: String): File = File(root, relativePath.substringBeforeLast('.') + THUMBNAIL_SUFFIX)

    fun delete(relativePath: String) {
        file(relativePath).delete()
        thumbnailFile(relativePath).delete()
    }

    fun deleteSession(sessionId: String) {
        sessionDirectory(sessionId).deleteRecursively()
    }

    companion object {
        const val DIRECTORY_NAME = "attachments"
        const val THUMBNAIL_SUFFIX = ".thumb.jpg"
    }
}

private val UNSAFE_SEGMENT_CHARS = Regex("[^A-Za-z0-9_-]")

private fun String.toSafeSegment(): String = replace(UNSAFE_SEGMENT_CHARS, "_").take(128).ifBlank { "_" }
