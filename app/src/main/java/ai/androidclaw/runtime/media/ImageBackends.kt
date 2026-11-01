package ai.androidclaw.runtime.media

import ai.androidclaw.BuildConfig
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

internal const val IMAGE_GENERATE_TOOL_NAME = "image_generate"
internal const val VIDEO_GENERATE_TOOL_NAME = "video_generate"

data class ImageGenerationRequest(
    val prompt: String,
    val size: String?,
    val quality: String?,
    val inputs: List<ImageInput> = emptyList(),
)

/** One image per call: a generation, or an edit when [ImageGenerationRequest.inputs] is not empty. */
fun interface ImageBackend {
    suspend fun generate(request: ImageGenerationRequest): ImageOutcome
}

/** Which backend serves `image_generate` / `video_generate` for the current turn. */
enum class MediaBackendMode {
    /** OpenAI Images and Videos with the saved OpenAI API key. */
    OpenAi,

    /** Debug builds with the offline FakeProvider active: a local Canvas PNG, no network. */
    Fake,

    Off,
}

/**
 * The media rule (PLANv13 M3/M5): debug builds on FakeProvider use the fake image backend;
 * otherwise the tools need a saved OpenAI API key, whatever chat provider is active.
 */
fun resolveMediaBackendMode(
    providerType: ProviderType,
    hasOpenAiKey: Boolean,
    fakeBackendAllowed: Boolean,
): MediaBackendMode =
    when {
        fakeBackendAllowed && providerType == ProviderType.Fake -> MediaBackendMode.Fake
        hasOpenAiKey -> MediaBackendMode.OpenAi
        else -> MediaBackendMode.Off
    }

/** True when [toolName] is offered to the model under this mode. Video has no fake backend. */
fun MediaBackendMode.offersTool(toolName: String): Boolean =
    when (toolName) {
        IMAGE_GENERATE_TOOL_NAME -> this != MediaBackendMode.Off
        VIDEO_GENERATE_TOOL_NAME -> this == MediaBackendMode.OpenAi
        else -> true
    }

/** Reads the inputs of [resolveMediaBackendMode] and the OpenAI credentials from storage. */
class MediaGenerationPolicy(
    private val settingsDataStore: SettingsDataStore,
    private val providerSecretStore: ProviderSecretStore,
    private val fakeBackendAllowed: Boolean = BuildConfig.DEBUG,
) {
    suspend fun credentials(): OpenAiMediaCredentials? {
        val key = providerSecretStore.readApiKey(ProviderType.OpenAi)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val baseUrl =
            settingsDataStore.settings
                .first()
                .endpointSettings(ProviderType.OpenAi)
                .baseUrl
        return OpenAiMediaCredentials(apiKey = key, baseUrl = baseUrl)
    }

    suspend fun resolve(providerType: ProviderType? = null): MediaBackendMode {
        val activeProvider = providerType ?: settingsDataStore.settings.first().providerType
        if (fakeBackendAllowed && activeProvider == ProviderType.Fake) return MediaBackendMode.Fake
        return resolveMediaBackendMode(
            providerType = activeProvider,
            hasOpenAiKey = !providerSecretStore.readApiKey(ProviderType.OpenAi).isNullOrBlank(),
            fakeBackendAllowed = fakeBackendAllowed,
        )
    }

    suspend fun imageModelId(): String = settingsDataStore.imageModelId.first()

    suspend fun videoModelId(): String = settingsDataStore.videoModelId.first()
}

/** OpenAI Images: generations, or multipart edits when inputs are given. */
class OpenAiImageBackend(
    private val client: OpenAiMediaClient,
    private val policy: MediaGenerationPolicy,
) : ImageBackend {
    override suspend fun generate(request: ImageGenerationRequest): ImageOutcome {
        val credentials =
            policy.credentials()
                ?: return ImageOutcome.Failure("DISABLED_BY_CONFIG", "image_generate needs an OpenAI API key in Settings → Provider.")
        val model = policy.imageModelId()
        return if (request.inputs.isEmpty()) {
            client.generateImage(credentials, model, request.prompt, request.size, request.quality)
        } else {
            client.editImage(credentials, model, request.prompt, request.size, request.quality, request.inputs)
        }
    }
}

private const val FAKE_IMAGE_LONG_SIDE = 512

/**
 * Debug-only offline backend: a deterministic PNG drawn with Canvas. The colour comes from the
 * prompt, the label shows the prompt start and, for edits, the input count.
 */
class FakeImageBackend : ImageBackend {
    override suspend fun generate(request: ImageGenerationRequest): ImageOutcome =
        withContext(Dispatchers.Default) {
            val (width, height) = fakeImageSize(request.size)
            val seed = request.prompt.hashCode()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                val hue = ((seed ushr 1) % 360).toFloat()
                canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.95f)))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = Color.HSVToColor(floatArrayOf((hue + 180f) % 360f, 0.6f, 0.75f))
                canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 3f, paint)
                paint.color = Color.BLACK
                paint.textSize = width / 16f
                val label = if (request.inputs.isEmpty()) "FAKE IMAGE" else "FAKE EDIT ×${request.inputs.size}"
                canvas.drawText(label, width / 16f, height / 10f, paint)
                canvas.drawText(request.prompt.take(28), width / 16f, height - height / 16f, paint)
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                ImageOutcome.Success(output.toByteArray(), "image/png")
            } finally {
                bitmap.recycle()
            }
        }
}

/** Keeps the requested aspect ratio at a 512 px long side. */
internal fun fakeImageSize(size: String?): Pair<Int, Int> {
    val parts = size?.split('x')?.mapNotNull(String::toIntOrNull)
    if (parts == null || parts.size != 2 || parts.any { it <= 0 }) return FAKE_IMAGE_LONG_SIDE to FAKE_IMAGE_LONG_SIDE
    val (w, h) = parts
    return if (w >= h) {
        FAKE_IMAGE_LONG_SIDE to (FAKE_IMAGE_LONG_SIDE * h / w).coerceAtLeast(1)
    } else {
        (FAKE_IMAGE_LONG_SIDE * w / h).coerceAtLeast(1) to FAKE_IMAGE_LONG_SIDE
    }
}
