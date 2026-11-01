package ai.androidclaw.runtime.media

import ai.androidclaw.data.attachments.AttachmentStore
import ai.androidclaw.data.attachments.IMPORT_THUMBNAIL_JPEG_QUALITY
import ai.androidclaw.data.attachments.IMPORT_THUMBNAIL_LONG_SIDE_PX
import ai.androidclaw.data.attachments.ImageImportMath
import ai.androidclaw.data.attachments.PixelSize
import ai.androidclaw.data.attachments.StagedAttachment
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Video facts read from the downloaded file; all fields are best effort. */
data class VideoFileInfo(
    val width: Int,
    val height: Int,
    val durationMs: Long?,
)

/**
 * Writes generated images and downloaded videos into the attachment folder of a session
 * (`filesDir/attachments/<sessionId>/`), plus a 256 px `<id>.thumb.jpg` poster.
 */
class GeneratedMediaStore(
    private val store: AttachmentStore,
    private val imageThumbnailWriter: (source: File, target: File) -> Unit = ::writeImageThumbnail,
    private val videoInspector: (source: File, thumbnail: File) -> VideoFileInfo? = ::inspectVideoAndWritePoster,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Saves image bytes; null when the bytes are not a PNG, JPEG or WebP image. */
    suspend fun saveImage(
        sessionId: String,
        bytes: ByteArray,
        mimeType: String,
    ): StagedAttachment? =
        withContext(Dispatchers.IO) {
            val size = ImageHeaders.sizeOf(bytes) ?: return@withContext null
            val actualMime = ImageHeaders.mimeTypeOf(bytes) ?: mimeType
            val id = newId()
            val extension = ImageHeaders.extensionFor(actualMime)
            val relativePath = store.relativePathFor(sessionId, id, extension)
            val file = store.file(relativePath)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            runCatching { imageThumbnailWriter(file, store.thumbnailFile(relativePath)) }
            StagedAttachment(
                id = id,
                sessionId = sessionId,
                displayName = "generated-${id.take(8)}.$extension",
                relativePath = relativePath,
                mimeType = actualMime,
                byteCount = bytes.size.toLong(),
                width = size.width,
                height = size.height,
            )
        }

    /** A fresh id and path for a video download. */
    fun newVideoTarget(sessionId: String): Pair<String, File> {
        val id = newId()
        return id to store.file(store.relativePathFor(sessionId, id, "mp4"))
    }

    /** Builds the attachment for a downloaded video and writes its poster frame. */
    suspend fun describeVideo(
        sessionId: String,
        id: String,
        file: File,
    ): Pair<StagedAttachment, Long?> =
        withContext(Dispatchers.IO) {
            val relativePath = store.relativePathFor(sessionId, id, "mp4")
            val info = runCatching { videoInspector(file, store.thumbnailFile(relativePath)) }.getOrNull()
            StagedAttachment(
                id = id,
                sessionId = sessionId,
                displayName = "video-${id.take(8)}.mp4",
                relativePath = relativePath,
                mimeType = "video/mp4",
                byteCount = file.length(),
                width = info?.width ?: 0,
                height = info?.height ?: 0,
            ) to info?.durationMs
        }

    fun delete(relativePath: String) = store.delete(relativePath)
}

/** Sampled decode to about 256 px, written as JPEG. */
internal fun writeImageThumbnail(
    source: File,
    target: File,
) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
    val sample = ImageImportMath.sampleSizeFor(PixelSize(bounds.outWidth, bounds.outHeight), IMPORT_THUMBNAIL_LONG_SIDE_PX)
    val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
    writeScaledJpeg(decoded, target)
}

/** Poster frame and metadata through the platform MediaMetadataRetriever (no Media3). */
internal fun inspectVideoAndWritePoster(
    source: File,
    thumbnail: File,
): VideoFileInfo? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(source.path)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        retriever.frameAtTime?.let { frame -> writeScaledJpeg(frame, thumbnail) }
        VideoFileInfo(width, height, duration)
    } finally {
        runCatching { retriever.release() }
    }
}

private fun writeScaledJpeg(
    bitmap: Bitmap,
    target: File,
) {
    val size = ImageImportMath.fitWithin(PixelSize(bitmap.width, bitmap.height), IMPORT_THUMBNAIL_LONG_SIDE_PX)
    val scaled = Bitmap.createScaledBitmap(bitmap, size.width, size.height, true)
    try {
        target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, IMPORT_THUMBNAIL_JPEG_QUALITY, it) }
    } finally {
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
    }
}
