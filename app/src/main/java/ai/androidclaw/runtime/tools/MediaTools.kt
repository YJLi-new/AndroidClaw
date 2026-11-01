package ai.androidclaw.runtime.tools

import ai.androidclaw.data.model.AttachmentKind
import ai.androidclaw.data.repository.AttachmentRepository
import ai.androidclaw.runtime.media.GeneratedMediaStore
import ai.androidclaw.runtime.media.IMAGE_GENERATE_TOOL_NAME
import ai.androidclaw.runtime.media.ImageBackend
import ai.androidclaw.runtime.media.ImageGenerationRequest
import ai.androidclaw.runtime.media.ImageInput
import ai.androidclaw.runtime.media.ImageOutcome
import ai.androidclaw.runtime.media.MediaBackendMode
import ai.androidclaw.runtime.media.MediaGenerationPolicy
import ai.androidclaw.runtime.media.OpenAiMediaClient
import ai.androidclaw.runtime.media.VIDEO_GENERATE_TOOL_NAME
import ai.androidclaw.runtime.media.VideoJobScheduler
import ai.androidclaw.runtime.media.VideoOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Clock

internal const val IMAGE_PROMPT_MAX_CHARS = 4_000
internal const val IMAGE_MAX_INPUTS = 4
internal val IMAGE_SIZES = listOf("1024x1024", "1536x1024", "1024x1536", "auto")
internal val IMAGE_QUALITIES = listOf("low", "medium", "high", "auto")
internal const val IMAGE_DEFAULT_SIZE = "1024x1024"
internal val VIDEO_SECONDS = listOf(4, 8, 12)
internal val VIDEO_SIZES = listOf("720x1280", "1280x720", "1024x1792", "1792x1024")

/** What `image_generate` and `video_generate` need at run time. */
class MediaToolDependencies(
    val policy: MediaGenerationPolicy,
    val attachmentRepository: AttachmentRepository,
    val mediaStore: GeneratedMediaStore,
    val openAiImageBackend: ImageBackend,
    val fakeImageBackend: ImageBackend,
    val client: OpenAiMediaClient,
    val videoJobScheduler: VideoJobScheduler,
    val clock: Clock = Clock.systemUTC(),
)

/**
 * `image_generate` and `video_generate`. Interactive only (each call costs money): neither is in
 * the scheduled tool set. AgentRunner offers them only when [MediaGenerationPolicy] allows it.
 */
internal fun mediaToolEntries(dependencies: MediaToolDependencies?): List<ToolRegistry.Entry> =
    listOf(
        imageGenerateToolEntry(dependencies),
        videoGenerateToolEntry(dependencies),
    )

private fun imageGenerateToolEntry(dependencies: MediaToolDependencies?): ToolRegistry.Entry =
    ToolRegistry.Entry(
        descriptor =
            ToolDescriptor(
                name = IMAGE_GENERATE_TOOL_NAME,
                description =
                    "Create an image from a prompt, or edit images given by attachment id. " +
                        "The user sees the image; you get its id. Each call costs money: use only when asked.",
                riskTier = ToolRiskTier.ExternalSideEffect,
                allowedOrigins = INTERACTIVE_ORIGINS,
                arguments =
                    listOf(
                        stringArg("prompt", "What to draw, or how to change the input images.", required = true),
                        stringArg("size", "Default 1024x1024.", enumValues = IMAGE_SIZES),
                        stringArg("quality", "Default auto.", enumValues = IMAGE_QUALITIES),
                        stringArg("input_attachment_ids", "Comma-separated image attachment ids to edit (max 4)."),
                    ),
            ),
    ) { context, arguments ->
        val deps = dependencies ?: return@Entry mediaUnavailable(IMAGE_GENERATE_TOOL_NAME)
        val sessionId = context.sessionId ?: return@Entry toolFailure("INVALID_CONTEXT", "image_generate needs a chat session.")
        val prompt = arguments.requireText("prompt", IMAGE_GENERATE_TOOL_NAME).take(IMAGE_PROMPT_MAX_CHARS)
        val size = arguments.optionalText("size") ?: IMAGE_DEFAULT_SIZE
        val quality = arguments.optionalText("quality")?.takeUnless { it == "auto" }
        val inputIds = parseAttachmentIds(arguments["input_attachment_ids"])
        if (inputIds.size > IMAGE_MAX_INPUTS) {
            return@Entry toolFailure("INVALID_ARGUMENTS", "image_generate accepts at most $IMAGE_MAX_INPUTS input images.")
        }
        val backend =
            when (deps.policy.resolve()) {
                MediaBackendMode.OpenAi -> deps.openAiImageBackend
                MediaBackendMode.Fake -> deps.fakeImageBackend
                MediaBackendMode.Off -> return@Entry mediaUnavailable(IMAGE_GENERATE_TOOL_NAME)
            }
        val inputs =
            inputIds.map { id ->
                loadInputImage(deps.attachmentRepository, sessionId, id)
                    ?: return@Entry toolFailure(
                        "ATTACHMENT_NOT_FOUND",
                        "No image attachment $id in this chat. Use an id from an [attachment …] marker or a generated image.",
                    )
            }
        when (val outcome = backend.generate(ImageGenerationRequest(prompt, size, quality, inputs))) {
            is ImageOutcome.Failure ->
                toolFailure(outcome.errorCode, outcome.message, buildJsonObject { outcome.httpStatus?.let { put("status", it) } })

            is ImageOutcome.Success -> {
                val staged =
                    deps.mediaStore.saveImage(sessionId, outcome.bytes, outcome.mimeType)
                        ?: return@Entry toolFailure("INVALID_IMAGE", "The image service returned data that is not a PNG, JPEG or WebP image.")
                ToolExecutionResult(
                    summary = "Generated image ${staged.id}, ${staged.width}×${staged.height}",
                    payload = buildJsonObject {},
                    attachments = listOf(ToolResultAttachment(staged, AttachmentKind.GeneratedImage)),
                )
            }
        }
    }

private fun videoGenerateToolEntry(dependencies: MediaToolDependencies?): ToolRegistry.Entry =
    ToolRegistry.Entry(
        descriptor =
            ToolDescriptor(
                name = VIDEO_GENERATE_TOOL_NAME,
                description =
                    "Start a short video from a prompt. Returns a job id at once; the video is added to " +
                        "this chat when ready (minutes). Costs money: use only when asked.",
                riskTier = ToolRiskTier.ExternalSideEffect,
                allowedOrigins = INTERACTIVE_ORIGINS,
                arguments =
                    listOf(
                        stringArg("prompt", "What the video shows.", required = true),
                        intArg("seconds", "4, 8 or 12. Default 4."),
                        stringArg("size", "Default 720x1280.", enumValues = VIDEO_SIZES),
                    ),
            ),
    ) { context, arguments ->
        val deps = dependencies ?: return@Entry mediaUnavailable(VIDEO_GENERATE_TOOL_NAME)
        val sessionId = context.sessionId ?: return@Entry toolFailure("INVALID_CONTEXT", "video_generate needs a chat session.")
        val prompt = arguments.requireText("prompt", VIDEO_GENERATE_TOOL_NAME).take(IMAGE_PROMPT_MAX_CHARS)
        val seconds =
            arguments.optionalLongOrNull("seconds")?.toInt()?.let { value ->
                require(value in VIDEO_SECONDS) { "video_generate seconds must be 4, 8 or 12." }
                value.toString()
            }
        val size = arguments.optionalText("size")
        if (deps.policy.resolve() != MediaBackendMode.OpenAi) return@Entry mediaUnavailable(VIDEO_GENERATE_TOOL_NAME)
        val credentials = deps.policy.credentials() ?: return@Entry mediaUnavailable(VIDEO_GENERATE_TOOL_NAME)
        when (val outcome = deps.client.createVideo(credentials, deps.policy.videoModelId(), prompt, seconds, size)) {
            is VideoOutcome.Failure ->
                toolFailure(outcome.errorCode, outcome.message, buildJsonObject { outcome.httpStatus?.let { put("status", it) } })

            is VideoOutcome.Success -> {
                deps.videoJobScheduler.enqueue(outcome.job.id, sessionId, prompt, deps.clock.millis())
                toolSuccess(
                    summary =
                        "Started video job ${outcome.job.id}. The video is added to this chat when it is ready " +
                            "(usually a few minutes); tell the user to wait.",
                    payload =
                        buildJsonObject {
                            put("jobId", outcome.job.id)
                            put("status", outcome.job.status.name)
                        },
                )
            }
        }
    }

private fun mediaUnavailable(toolName: String): ToolExecutionResult =
    toolFailure(
        "DISABLED_BY_CONFIG",
        "$toolName needs an OpenAI API key saved in Settings → Provider (OpenAI).",
    )

private val ATTACHMENT_MARKER_ID = Regex("""\[attachment\s+([^:\]\s]+)""")

/** Accepts `"a,b"`, `"a b"`, pasted `[attachment <id>: …]` markers, or a JSON array of ids. */
internal fun parseAttachmentIds(value: kotlinx.serialization.json.JsonElement?): List<String> {
    val text =
        when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(",")
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            is JsonObject, null -> ""
        }
    val markerIds = ATTACHMENT_MARKER_ID.findAll(text).map { it.groupValues[1] }.toList()
    val ids = markerIds.ifEmpty { text.split(',', ';', ' ', '\n', '\t') }
    return ids.map(String::trim).filter(String::isNotEmpty).distinct()
}

private suspend fun loadInputImage(
    repository: AttachmentRepository,
    sessionId: String,
    id: String,
): ImageInput? {
    val attachment = repository.getAttachment(id) ?: return null
    if (attachment.sessionId != sessionId) return null
    if (attachment.kind != AttachmentKind.UserImage && attachment.kind != AttachmentKind.GeneratedImage) return null
    val file = repository.file(attachment.relativePath)
    val bytes = withContext(Dispatchers.IO) { file.takeIf { it.isFile }?.readBytes() } ?: return null
    return ImageInput(fileName = attachment.displayName, mimeType = attachment.mimeType, bytes = bytes)
}
