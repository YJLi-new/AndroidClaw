package ai.androidclaw.runtime.providers

import java.io.File
import java.util.Base64

/**
 * An image on a user message. [file] is set only for images whose bytes go into this request
 * (see [withImageRequestBound]); every other image travels as its text [marker].
 */
data class ModelAttachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteCount: Long,
    val relativePath: String,
    val file: File? = null,
)

internal const val MAX_REQUEST_IMAGE_TURNS = 2
internal const val MAX_REQUEST_IMAGES = 4
internal const val MAX_REQUEST_ENCODED_IMAGE_BYTES = 8L * 1024 * 1024

/** `[attachment <id>: <name> <w>×<h>]`, so the model can name an image in later tool calls. */
fun ModelAttachment.marker(): String = "[attachment $id: $displayName $width×$height]"

internal fun ModelAttachment.encodedByteCount(): Long = ((byteCount + 2) / 3) * 4

/** Reads the file on the calling (IO) thread. Null when the image is not sent or is gone. */
internal fun ModelAttachment.base64DataOrNull(): String? {
    val source = file ?: return null
    return try {
        Base64.getEncoder().encodeToString(source.readBytes())
    } catch (_: java.io.IOException) {
        null
    }
}

internal fun ModelAttachment.dataUrlOrNull(): String? = base64DataOrNull()?.let { "data:$mimeType;base64,$it" }

/** Message text plus one marker line per attachment: the text-only form of an image turn. */
fun ModelMessage.contentWithAttachmentMarkers(): String {
    if (attachments.isEmpty()) return content
    return buildString {
        append(content)
        attachments.forEach { attachment ->
            if (isNotEmpty()) append('\n')
            append(attachment.marker())
        }
    }
}

/** True when at least one attachment carries bytes in this request. */
internal fun ModelMessage.hasInlineImages(): Boolean = attachments.any { it.file != null }

/**
 * Request bound: image bytes are sent only for the latest [maxTurns] user turns that carry
 * images, at most [maxImages] images and [maxEncodedBytes] of base64 per request. Older images,
 * images over the bound, and every image when [imagesEnabled] is false keep only their marker.
 * [resolveFile] maps a stored relative path to its file (or null when missing).
 */
fun List<ModelMessage>.withImageRequestBound(
    imagesEnabled: Boolean,
    resolveFile: (ModelAttachment) -> File?,
    maxTurns: Int = MAX_REQUEST_IMAGE_TURNS,
    maxImages: Int = MAX_REQUEST_IMAGES,
    maxEncodedBytes: Long = MAX_REQUEST_ENCODED_IMAGE_BYTES,
): List<ModelMessage> {
    if (none { it.attachments.isNotEmpty() }) return this
    var turns = 0
    var images = 0
    var encodedBytes = 0L
    val result = toMutableList()
    for (index in result.indices.reversed()) {
        val message = result[index]
        if (message.attachments.isEmpty()) continue
        val eligibleTurn = imagesEnabled && message.role == ModelMessageRole.User && turns < maxTurns
        if (eligibleTurn) turns += 1
        result[index] =
            message.copy(
                attachments =
                    message.attachments.map { attachment ->
                        val size = attachment.encodedByteCount()
                        val file =
                            if (eligibleTurn && images < maxImages && encodedBytes + size <= maxEncodedBytes) {
                                resolveFile(attachment)?.takeIf(File::isFile)
                            } else {
                                null
                            }
                        if (file != null) {
                            images += 1
                            encodedBytes += size
                        }
                        attachment.copy(file = file)
                    },
            )
    }
    return result
}

private val IMAGE_REJECTION_HINTS =
    listOf("image", "vision", "multimodal", "multi-modal", "input_image", "image_url", "图片", "图像")

/**
 * A 4xx that names images (the model or endpoint has no vision support). Authentication,
 * not-found and rate-limit statuses keep their own meaning.
 */
internal fun isProviderImageRejection(
    statusCode: Int,
    errorMessage: String,
): Boolean {
    if (statusCode !in 400..499 || statusCode in setOf(401, 403, 404, 408, 429)) return false
    val normalized = errorMessage.lowercase()
    return IMAGE_REJECTION_HINTS.any { normalized.contains(it) }
}

internal fun imagesUnsupportedFailure(details: String): ModelProviderException =
    ModelProviderException(
        kind = ModelProviderFailureKind.ImagesUnsupported,
        userMessage = "The model rejected the attached image. Choose a vision model or turn off images for this provider.",
        details = details.take(MAX_PROVIDER_ERROR_BODY_CHARS),
    )
