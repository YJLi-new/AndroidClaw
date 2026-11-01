package ai.androidclaw.runtime.media

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val IMAGE_CALL_TIMEOUT_SECONDS = 180L
internal const val VIDEO_DOWNLOAD_MAX_BYTES = 50L * 1024 * 1024
private const val IMAGE_RESPONSE_MAX_BYTES = 40L * 1024 * 1024
private const val VIDEO_JSON_MAX_BYTES = 256L * 1024
private const val ERROR_DETAIL_MAX_CHARS = 200
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** The saved OpenAI API key and the OpenAI provider's base URL (default `https://api.openai.com/v1`). */
data class OpenAiMediaCredentials(
    val apiKey: String,
    val baseUrl: String,
) {
    override fun toString(): String = "OpenAiMediaCredentials(baseUrl=$baseUrl)"
}

class ImageInput(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

sealed interface ImageOutcome {
    class Success(
        val bytes: ByteArray,
        val mimeType: String,
    ) : ImageOutcome

    data class Failure(
        val errorCode: String,
        val message: String,
        val httpStatus: Int? = null,
    ) : ImageOutcome
}

enum class VideoJobStatus {
    Queued,
    InProgress,
    Completed,
    Failed,
    Unknown,
}

data class VideoJob(
    val id: String,
    val status: VideoJobStatus,
    val progress: Int? = null,
    val seconds: String? = null,
    val size: String? = null,
    val errorMessage: String? = null,
)

sealed interface VideoOutcome {
    data class Success(
        val job: VideoJob,
    ) : VideoOutcome

    data class Failure(
        val errorCode: String,
        val message: String,
        val httpStatus: Int? = null,
    ) : VideoOutcome
}

sealed interface DownloadOutcome {
    data class Success(
        val byteCount: Long,
    ) : DownloadOutcome

    data class Failure(
        val errorCode: String,
        val message: String,
        val httpStatus: Int? = null,
    ) : DownloadOutcome
}

/**
 * OpenAI Images (`images/generations`, multipart `images/edits`) and Videos (`videos`) on the
 * plain HTTP API. Calls are cancelled when the calling coroutine is cancelled (Stop).
 */
class OpenAiMediaClient(
    baseClient: OkHttpClient? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val imageClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        (baseClient ?: OkHttpClient())
            .newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(IMAGE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(IMAGE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    private val videoClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        (baseClient ?: OkHttpClient())
            .newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    suspend fun generateImage(
        credentials: OpenAiMediaCredentials,
        model: String,
        prompt: String,
        size: String?,
        quality: String?,
    ): ImageOutcome {
        val url = credentials.url("images/generations") ?: return ImageOutcome.Failure("INVALID_CONFIG", invalidBaseUrl(credentials))
        val body =
            buildJsonObject {
                put("model", model)
                put("prompt", prompt)
                put("n", 1)
                size?.let { put("size", it) }
                quality?.let { put("quality", it) }
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
        return imageCall(credentials.post(url, body))
    }

    suspend fun editImage(
        credentials: OpenAiMediaCredentials,
        model: String,
        prompt: String,
        size: String?,
        quality: String?,
        images: List<ImageInput>,
    ): ImageOutcome {
        val url = credentials.url("images/edits") ?: return ImageOutcome.Failure("INVALID_CONFIG", invalidBaseUrl(credentials))
        val body =
            MultipartBody
                .Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("n", "1")
                .apply {
                    size?.let { addFormDataPart("size", it) }
                    quality?.let { addFormDataPart("quality", it) }
                    images.forEach { image ->
                        addFormDataPart("image[]", image.fileName, image.bytes.toRequestBody(image.mimeType.toMediaType()))
                    }
                }.build()
        return imageCall(credentials.post(url, body))
    }

    suspend fun createVideo(
        credentials: OpenAiMediaCredentials,
        model: String,
        prompt: String,
        seconds: String?,
        size: String?,
    ): VideoOutcome {
        val url = credentials.url("videos") ?: return VideoOutcome.Failure("INVALID_CONFIG", invalidBaseUrl(credentials))
        val body =
            MultipartBody
                .Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("prompt", prompt)
                .apply {
                    seconds?.let { addFormDataPart("seconds", it) }
                    size?.let { addFormDataPart("size", it) }
                }.build()
        return videoCall(credentials.post(url, body))
    }

    suspend fun retrieveVideo(
        credentials: OpenAiMediaCredentials,
        jobId: String,
    ): VideoOutcome {
        val url =
            credentials
                .url("videos")
                ?.newBuilder()
                ?.addPathSegment(jobId)
                ?.build()
                ?: return VideoOutcome.Failure("INVALID_CONFIG", invalidBaseUrl(credentials))
        return videoCall(credentials.get(url))
    }

    /** Streams `videos/{id}/content` into [target]; deletes it again on failure or past [maxBytes]. */
    suspend fun downloadVideo(
        credentials: OpenAiMediaCredentials,
        jobId: String,
        target: File,
        maxBytes: Long = VIDEO_DOWNLOAD_MAX_BYTES,
    ): DownloadOutcome {
        val url =
            credentials
                .url("videos")
                ?.newBuilder()
                ?.addPathSegment(jobId)
                ?.addPathSegment("content")
                ?.build()
                ?: return DownloadOutcome.Failure("INVALID_CONFIG", invalidBaseUrl(credentials))
        return try {
            videoClient.await(credentials.get(url, accept = "video/mp4")) { response ->
                if (!response.isSuccessful) {
                    val raw = response.readBounded(VIDEO_JSON_MAX_BYTES)
                    return@await DownloadOutcome.Failure("HTTP_ERROR", httpErrorMessage("video download", response.code, raw), response.code)
                }
                val source = response.body?.source() ?: return@await DownloadOutcome.Failure("FETCH_FAILED", "Empty video response.")
                target.parentFile?.mkdirs()
                var written = 0L
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    val stream = source.inputStream()
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > maxBytes) {
                            output.close()
                            target.delete()
                            return@await DownloadOutcome.Failure("TOO_LARGE", "The video is larger than ${maxBytes / (1024 * 1024)} MB.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
                DownloadOutcome.Success(written)
            }
        } catch (error: IOException) {
            target.delete()
            val failure = error.toFailure(url)
            DownloadOutcome.Failure(failure.first, failure.second)
        }
    }

    private suspend fun imageCall(request: Request): ImageOutcome =
        try {
            imageClient.await(request) { response ->
                val raw = response.readBounded(IMAGE_RESPONSE_MAX_BYTES)
                if (!response.isSuccessful) {
                    ImageOutcome.Failure("HTTP_ERROR", httpErrorMessage("OpenAI Images", response.code, raw), response.code)
                } else {
                    parseImageResponse(raw)
                }
            }
        } catch (error: IOException) {
            val failure = error.toFailure(request.url)
            ImageOutcome.Failure(failure.first, failure.second)
        }

    private suspend fun videoCall(request: Request): VideoOutcome =
        try {
            videoClient.await(request) { response ->
                val raw = response.readBounded(VIDEO_JSON_MAX_BYTES)
                if (!response.isSuccessful) {
                    VideoOutcome.Failure("HTTP_ERROR", httpErrorMessage("OpenAI Videos", response.code, raw), response.code)
                } else {
                    parseVideoJob(raw)?.let(VideoOutcome::Success)
                        ?: VideoOutcome.Failure("FETCH_FAILED", "OpenAI Videos returned malformed JSON.")
                }
            }
        } catch (error: IOException) {
            val failure = error.toFailure(request.url)
            VideoOutcome.Failure(failure.first, failure.second)
        }

    private fun parseImageResponse(raw: String): ImageOutcome {
        val root =
            runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return ImageOutcome.Failure("FETCH_FAILED", "OpenAI Images returned malformed JSON.")
        val first = (root["data"] as? JsonArray)?.firstOrNull() as? JsonObject
        val base64 =
            first?.text("b64_json")
                ?: return ImageOutcome.Failure("FETCH_FAILED", "OpenAI Images returned no image data.")
        val bytes =
            runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
                ?: return ImageOutcome.Failure("FETCH_FAILED", "OpenAI Images returned invalid base64.")
        val mimeType =
            when (root.text("output_format")?.lowercase()) {
                "jpeg", "jpg" -> "image/jpeg"
                "webp" -> "image/webp"
                "png" -> "image/png"
                else -> ImageHeaders.mimeTypeOf(bytes) ?: "image/png"
            }
        return ImageOutcome.Success(bytes, mimeType)
    }

    internal fun parseVideoJob(raw: String): VideoJob? {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val id = root.text("id") ?: return null
        return VideoJob(
            id = id,
            status =
                when (root.text("status")?.lowercase()) {
                    "queued" -> VideoJobStatus.Queued
                    "in_progress", "processing" -> VideoJobStatus.InProgress
                    "completed", "succeeded" -> VideoJobStatus.Completed
                    "failed", "cancelled", "canceled", "expired" -> VideoJobStatus.Failed
                    else -> VideoJobStatus.Unknown
                },
            progress = (root["progress"] as? JsonPrimitive)?.intOrNull,
            seconds = root.text("seconds"),
            size = root.text("size"),
            errorMessage = (root["error"] as? JsonObject)?.text("message")?.take(ERROR_DETAIL_MAX_CHARS),
        )
    }

    private fun httpErrorMessage(
        api: String,
        status: Int,
        raw: String,
    ): String {
        val detail =
            runCatching {
                ((json.parseToJsonElement(raw) as? JsonObject)?.get("error") as? JsonObject)?.text("message")
            }.getOrNull()
        val hint =
            when (status) {
                401, 403 -> "the OpenAI API key was rejected"
                404 -> "the model or job was not found"
                429 -> "the OpenAI rate limit or quota was reached"
                in 400..499 -> "the request was rejected"
                else -> "the service failed"
            }
        return buildString {
            append("HTTP ")
                .append(status)
                .append(" from ")
                .append(api)
                .append(": ")
                .append(hint)
            detail?.takeIf { it.isNotBlank() }?.let {
                append(" (").append(it.replace('\n', ' ').take(ERROR_DETAIL_MAX_CHARS)).append(')')
            }
            append('.')
        }
    }
}

private fun OpenAiMediaCredentials.url(path: String): HttpUrl? =
    baseUrl
        .trim()
        .trimEnd('/')
        .toHttpUrlOrNull()
        ?.newBuilder()
        ?.addPathSegments(path)
        ?.build()

private fun OpenAiMediaCredentials.post(
    url: HttpUrl,
    body: okhttp3.RequestBody,
): Request =
    Request
        .Builder()
        .url(url)
        .header("Authorization", "Bearer $apiKey")
        .header("Accept", "application/json")
        .post(body)
        .build()

private fun OpenAiMediaCredentials.get(
    url: HttpUrl,
    accept: String = "application/json",
): Request =
    Request
        .Builder()
        .url(url)
        .header("Authorization", "Bearer $apiKey")
        .header("Accept", accept)
        .get()
        .build()

private fun invalidBaseUrl(credentials: OpenAiMediaCredentials): String = "The OpenAI base URL \"${credentials.baseUrl.take(120)}\" is not a valid URL."

private fun IOException.toFailure(url: HttpUrl): Pair<String, String> =
    when (this) {
        is UnknownHostException -> "FETCH_BLOCKED_OR_UNKNOWN_HOST" to (message ?: "Unknown host ${url.host}.")
        is InterruptedIOException -> "TIMEOUT" to "The request to ${url.host} timed out."
        else -> "FETCH_FAILED" to "Could not reach ${url.host}: ${message ?: javaClass.simpleName}."
    }

private fun Response.readBounded(maxBytes: Long): String {
    val source = body?.source() ?: return ""
    source.request(maxBytes)
    val buffer = source.buffer
    return buffer.readUtf8(buffer.size.coerceAtMost(maxBytes))
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** Enqueues the call and cancels it when the coroutine is cancelled; [read] runs on OkHttp's thread. */
private suspend fun <T> OkHttpClient.await(
    request: Request,
    read: (Response) -> T,
): T =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    val result = runCatching { response.use(read) }
                    if (!continuation.isActive) return
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                }
            },
        )
    }
