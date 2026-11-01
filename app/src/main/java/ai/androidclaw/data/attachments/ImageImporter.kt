package ai.androidclaw.data.attachments

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/** An imported image on disk that is not yet linked to a message (a composer chip). */
data class StagedAttachment(
    val id: String,
    val sessionId: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val byteCount: Long,
    val width: Int,
    val height: Int,
)

class AttachmentImportException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

interface AttachmentImporter {
    suspend fun importImage(
        sessionId: String,
        uri: Uri,
    ): StagedAttachment
}

/**
 * Photo import: bounds-only decode, power-of-two sampled decode (never a full-resolution bitmap),
 * EXIF orientation applied, re-encoded as JPEG (which drops all metadata), long side at most
 * [IMPORT_MAX_LONG_SIDE_PX], at most [IMPORT_MAX_BYTES], plus a 256 px thumbnail.
 */
class AndroidImageImporter(
    private val contentResolver: ContentResolver,
    private val store: AttachmentStore,
) : AttachmentImporter {
    override suspend fun importImage(
        sessionId: String,
        uri: Uri,
    ): StagedAttachment = withContext(Dispatchers.IO) { importBlocking(sessionId, uri) }

    private fun importBlocking(
        sessionId: String,
        uri: Uri,
    ): StagedAttachment {
        val source = readBounds(uri)
        val orientation = readExifOrientation(uri)
        val sampleSize = ImageImportMath.sampleSizeFor(source)
        val sampled =
            openStream(uri).use { stream ->
                BitmapFactory.decodeStream(
                    stream,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            } ?: throw AttachmentImportException("The image could not be decoded.")
        val oriented = sampled.scaledAndOriented(IMPORT_MAX_LONG_SIDE_PX, orientation)
        if (oriented !== sampled) {
            sampled.recycle()
        }
        try {
            val id = UUID.randomUUID().toString()
            val relativePath = store.relativePathFor(sessionId, id)
            val target = store.file(relativePath)
            target.parentFile?.mkdirs()
            val (bytes, finalBitmap) = encodeWithinLimit(oriented)
            try {
                target.writeBytes(bytes)
                writeThumbnail(finalBitmap, store.thumbnailFile(relativePath))
                return StagedAttachment(
                    id = id,
                    sessionId = sessionId,
                    displayName = displayNameFor(uri),
                    relativePath = relativePath,
                    mimeType = JPEG_MIME_TYPE,
                    byteCount = bytes.size.toLong(),
                    width = finalBitmap.width,
                    height = finalBitmap.height,
                )
            } catch (error: IOException) {
                store.delete(relativePath)
                throw error
            } finally {
                if (finalBitmap !== oriented) {
                    finalBitmap.recycle()
                }
            }
        } finally {
            oriented.recycle()
        }
    }

    private fun readBounds(uri: Uri): PixelSize {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri).use { stream -> BitmapFactory.decodeStream(stream, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw AttachmentImportException("The file is not a supported image.")
        }
        return PixelSize(options.outWidth, options.outHeight)
    }

    private fun readExifOrientation(uri: Uri): Int =
        try {
            openStream(uri).use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }

    private fun openStream(uri: Uri) =
        try {
            contentResolver.openInputStream(uri)
        } catch (error: SecurityException) {
            throw AttachmentImportException("The image is not readable.", error)
        } ?: throw AttachmentImportException("The image is not readable.")

    /** Re-encodes at falling quality, then at 80% size, until the JPEG fits [IMPORT_MAX_BYTES]. */
    private fun encodeWithinLimit(bitmap: Bitmap): Pair<ByteArray, Bitmap> {
        var current = bitmap
        repeat(MAX_DOWNSCALE_ROUNDS) {
            for (quality in ImageImportMath.qualityLadder) {
                val bytes = current.toJpeg(quality)
                if (bytes.size <= IMPORT_MAX_BYTES) {
                    return bytes to current
                }
            }
            val next =
                Bitmap.createScaledBitmap(
                    current,
                    (current.width * DOWNSCALE_FACTOR).toInt().coerceAtLeast(1),
                    (current.height * DOWNSCALE_FACTOR).toInt().coerceAtLeast(1),
                    true,
                )
            if (current !== bitmap) {
                current.recycle()
            }
            current = next
        }
        throw AttachmentImportException("The image is too large to attach.")
    }

    private fun writeThumbnail(
        bitmap: Bitmap,
        file: File,
    ) {
        val size = ImageImportMath.fitWithin(PixelSize(bitmap.width, bitmap.height), IMPORT_THUMBNAIL_LONG_SIDE_PX)
        val thumbnail = Bitmap.createScaledBitmap(bitmap, size.width, size.height, true)
        try {
            file.writeBytes(thumbnail.toJpeg(IMPORT_THUMBNAIL_JPEG_QUALITY))
        } finally {
            if (thumbnail !== bitmap) {
                thumbnail.recycle()
            }
        }
    }

    private fun displayNameFor(uri: Uri): String {
        val queried =
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
        val raw = queried ?: uri.lastPathSegment?.substringAfterLast('/') ?: DEFAULT_DISPLAY_NAME
        val stem =
            raw
                .substringBeforeLast('.')
                .replace(Regex("[\\[\\]\\r\\n]"), " ")
                .trim()
                .take(MAX_DISPLAY_NAME_STEM_CHARS)
                .ifBlank { "image" }
        return "$stem.jpg"
    }

    private companion object {
        const val JPEG_MIME_TYPE = "image/jpeg"
        const val DEFAULT_DISPLAY_NAME = "image.jpg"
        const val MAX_DISPLAY_NAME_STEM_CHARS = 80
        const val MAX_DOWNSCALE_ROUNDS = 4
        const val DOWNSCALE_FACTOR = 0.8
    }
}

private fun Bitmap.toJpeg(quality: Int): ByteArray =
    ByteArrayOutputStream(DEFAULT_JPEG_BUFFER_BYTES).use { output ->
        compress(Bitmap.CompressFormat.JPEG, quality, output)
        output.toByteArray()
    }

private const val DEFAULT_JPEG_BUFFER_BYTES = 256 * 1024

/** One matrix pass: scale to [maxLongSide] and apply the EXIF orientation. */
private fun Bitmap.scaledAndOriented(
    maxLongSide: Int,
    exifOrientation: Int,
): Bitmap {
    val target = ImageImportMath.fitWithin(PixelSize(width, height), maxLongSide)
    val matrix = Matrix()
    if (target.width != width || target.height != height) {
        matrix.postScale(target.width.toFloat() / width, target.height.toFloat() / height)
    }
    when (exifOrientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }
    if (matrix.isIdentity) {
        return this
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
