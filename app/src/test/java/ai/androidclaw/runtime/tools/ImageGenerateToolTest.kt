package ai.androidclaw.runtime.tools

import ai.androidclaw.data.IMAGE_GENERATION_DEFAULT_MODEL_ID
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.attachments.AttachmentStore
import ai.androidclaw.data.attachments.StagedAttachment
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.AttachmentKind
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.AttachmentRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.runtime.media.GeneratedMediaStore
import ai.androidclaw.runtime.media.IMAGE_GENERATE_TOOL_NAME
import ai.androidclaw.runtime.media.ImageBackend
import ai.androidclaw.runtime.media.ImageGenerationRequest
import ai.androidclaw.runtime.media.ImageOutcome
import ai.androidclaw.runtime.media.MediaGenerationPolicy
import ai.androidclaw.runtime.media.OpenAiMediaClient
import ai.androidclaw.runtime.media.VIDEO_GENERATE_TOOL_NAME
import ai.androidclaw.runtime.media.VideoJobScheduler
import ai.androidclaw.runtime.media.multipartField
import ai.androidclaw.runtime.media.offersTool
import ai.androidclaw.runtime.media.testImageBytes
import ai.androidclaw.runtime.media.videoJson
import ai.androidclaw.runtime.orchestrator.AgentRunner
import ai.androidclaw.runtime.orchestrator.AgentTurnRequest
import ai.androidclaw.runtime.orchestrator.PromptAssembler
import ai.androidclaw.runtime.orchestrator.SessionLaneCoordinator
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.providers.ModelRunMode
import ai.androidclaw.runtime.skills.createTestSkillManager
import ai.androidclaw.testutil.InMemoryProviderSecretStore
import ai.androidclaw.testutil.buildTestProviderRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** image_generate: result shape, attachment linking, edits by id, and when AgentRunner offers it. */
@RunWith(AndroidJUnit4::class)
class ImageGenerateToolTest {
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var root: File
    private lateinit var attachments: AttachmentRepository
    private lateinit var messages: MessageRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var sessionId: String
    private val secrets = InMemoryProviderSecretStore()
    private val backendRequests = mutableListOf<ImageGenerationRequest>()
    private val backend =
        ImageBackend { request ->
            backendRequests += request
            ImageOutcome.Success(testImageBytes(64, 48), "image/png")
        }

    @Before
    fun setUp() =
        runTest {
            application = ApplicationProvider.getApplicationContext()
            database = buildTestDatabase(application)
            root = Files.createTempDirectory("gen-images").toFile()
            attachments = AttachmentRepository(database.messageAttachmentDao(), AttachmentStore(root))
            messages = MessageRepository(database.messageDao(), attachments)
            settingsDataStore = SettingsDataStore(application)
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            sessionId = SessionRepository(database.sessionDao(), attachments).createSession("Images").id
        }

    @After
    fun tearDown() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            database.close()
            root.deleteRecursively()
        }

    @Test
    fun `generation saves the file and gives the model only a short line`() =
        runTest {
            val result = execute(buildJsonObject { put("prompt", "a red apple") })

            assertTrue(result.success)
            val staged = result.attachments.single()
            assertEquals(AttachmentKind.GeneratedImage, staged.kind)
            assertEquals("Generated image ${staged.staged.id}, 64×48", result.toModelContent())
            assertTrue(attachments.file(staged.staged.relativePath).isFile)
            assertEquals("image/png", staged.staged.mimeType)
            assertEquals("1024x1024", backendRequests.single().size)
            assertEquals(null, backendRequests.single().quality)
        }

    @Test
    fun `edit sends the referenced images and rejects foreign ids`() =
        runTest {
            val input = storeUserImage("photo1", sessionId)
            val otherSession = SessionRepository(database.sessionDao(), attachments).createSession("Other").id
            storeUserImage("photo2", otherSession)

            val edited =
                execute(
                    buildJsonObject {
                        put("prompt", "make it green")
                        put("input_attachment_ids", "[attachment ${input.id}: photo1.jpg 8×8]")
                        put("quality", "low")
                    },
                )
            assertTrue(edited.summary, edited.success)
            val request = backendRequests.single()
            assertEquals(1, request.inputs.size)
            assertEquals("image/jpeg", request.inputs.single().mimeType)
            assertEquals("low", request.quality)

            val foreign =
                execute(
                    buildJsonObject {
                        put("prompt", "x")
                        put("input_attachment_ids", "photo2")
                    },
                )
            assertEquals("ATTACHMENT_NOT_FOUND", foreign.errorCode)
            val tooMany =
                execute(
                    buildJsonObject {
                        put("prompt", "x")
                        put("input_attachment_ids", "a,b,c,d,e")
                    },
                )
            assertEquals("INVALID_ARGUMENTS", tooMany.errorCode)
        }

    @Test
    fun `without an OpenAI key and off the fake provider the tool reports config`() =
        runTest {
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot(providerType = ProviderType.DeepSeek))
            val result = execute(buildJsonObject { put("prompt", "x") })
            assertEquals("DISABLED_BY_CONFIG", result.errorCode)
            assertTrue(backendRequests.isEmpty())
        }

    @Test
    fun `backend failures pass through with status`() =
        runTest {
            val failing = ImageBackend { ImageOutcome.Failure("HTTP_ERROR", "HTTP 400 from OpenAI Images: rejected.", 400) }
            val result = registry(fake = failing).execute(context(), buildJsonObject { put("prompt", "x") })
            assertEquals("HTTP_ERROR", result.errorCode)
            assertTrue(result.attachments.isEmpty())
        }

    @Test
    fun `fake provider scripted path links the image to the tool result row`() =
        runTest {
            val result = runner().runInteractiveTurn(AgentTurnRequest(sessionId, "Draw a red fox [tool:image_generate]"))

            assertEquals("Draw a red fox", backendRequests.single().prompt)
            val toolResult = messages.getMessages(sessionId).single { it.role == MessageRole.ToolResult }
            val attachment = toolResult.attachments.single()
            assertEquals(AttachmentKind.GeneratedImage, attachment.kind)
            assertTrue(toolResult.content.startsWith("Tool result: Generated image ${attachment.id}, 64×48"))
            assertTrue(result.assistantMessage.contains("Generated image"))
        }

    @Test
    fun `runner offers media tools per policy and never in scheduled runs`() =
        runTest {
            val seen = mutableListOf<ModelRequest>()
            val recorder =
                object : ModelProvider {
                    override val id: String = ProviderType.Anthropic.providerId

                    override suspend fun generate(request: ModelRequest): ModelResponse {
                        seen += request
                        return ModelResponse(text = "ok")
                    }
                }
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot(providerType = ProviderType.Anthropic))
            val runner = runner(anthropic = recorder)

            runner.runInteractiveTurn(AgentTurnRequest(sessionId, "no key yet"))
            assertFalse(seen.last().offers(IMAGE_GENERATE_TOOL_NAME) || seen.last().offers(VIDEO_GENERATE_TOOL_NAME))

            secrets.writeApiKey(ProviderType.OpenAi, "sk-test")
            runner.runInteractiveTurn(AgentTurnRequest(sessionId, "with key"))
            assertTrue(seen.last().offers(IMAGE_GENERATE_TOOL_NAME) && seen.last().offers(VIDEO_GENERATE_TOOL_NAME))

            runner.runScheduledTurn(sessionId, "scheduled")
            assertEquals(ModelRunMode.Scheduled, seen.last().runMode)
            assertFalse(seen.last().offers(IMAGE_GENERATE_TOOL_NAME) || seen.last().offers(VIDEO_GENERATE_TOOL_NAME))
        }

    @Test
    fun `fake mode offers images only and model ids default from the probe`() =
        runTest {
            val policy = policy()
            val registry = registry()
            assertEquals(IMAGE_GENERATION_DEFAULT_MODEL_ID, settingsDataStore.imageModelId.first())
            settingsDataStore.setImageModelId("  gpt-image-1  ")
            assertEquals("gpt-image-1", policy.imageModelId())
            settingsDataStore.setImageModelId("")
            assertEquals(IMAGE_GENERATION_DEFAULT_MODEL_ID, policy.imageModelId())
            val mode = policy.resolve()
            val offered = registry.descriptorsFor(ToolInvocationOrigin.Model, ModelRunMode.Interactive).filter { mode.offersTool(it.name) }
            assertTrue(offered.any { it.name == IMAGE_GENERATE_TOOL_NAME })
            assertFalse(offered.any { it.name == VIDEO_GENERATE_TOOL_NAME })
        }

    @Test
    fun `video_generate creates the job and enqueues the worker`() =
        runTest {
            val server = MockWebServer().also { it.start() }
            try {
                server.enqueue(MockResponse().setBody(videoJson("video_7", "queued")))
                secrets.writeApiKey(ProviderType.OpenAi, "sk-test")
                val snapshot = ProviderSettingsSnapshot(providerType = ProviderType.Anthropic)
                settingsDataStore.saveProviderSettings(
                    snapshot.withEndpointSettings(
                        ProviderType.OpenAi,
                        snapshot.endpointSettings(ProviderType.OpenAi).copy(baseUrl = server.url("/v1").toString()),
                    ),
                )
                val enqueued = mutableListOf<String>()
                val registry = registry(videoJobScheduler = { jobId, session, _, _ -> enqueued += "$jobId@$session" })

                val result =
                    registry.execute(
                        context().copy(requestedName = VIDEO_GENERATE_TOOL_NAME, canonicalName = VIDEO_GENERATE_TOOL_NAME),
                        buildJsonObject {
                            put("prompt", "a cat surfing")
                            put("seconds", 8)
                        },
                    )

                assertTrue(result.summary, result.success)
                assertTrue(result.summary.startsWith("Started video job video_7."))
                assertEquals(listOf("video_7@$sessionId"), enqueued)
                val body = server.takeRequest().body.readUtf8()
                assertEquals("sora-2", multipartField(body, "model"))
                assertEquals("8", multipartField(body, "seconds"))

                val badSeconds =
                    registry.execute(
                        context().copy(requestedName = VIDEO_GENERATE_TOOL_NAME, canonicalName = VIDEO_GENERATE_TOOL_NAME),
                        buildJsonObject {
                            put("prompt", "x")
                            put("seconds", 5)
                        },
                    )
                assertEquals("INVALID_ARGUMENTS", badSeconds.errorCode)
            } finally {
                server.shutdown()
            }
        }

    private fun ModelRequest.offers(name: String): Boolean = toolDescriptors.any { it.name == name }

    private suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        backendRequests.clear()
        return registry().execute(context(), arguments)
    }

    private fun context() =
        ToolExecutionContext(
            sessionId = sessionId,
            taskRunId = null,
            origin = ToolInvocationOrigin.Model,
            runMode = ModelRunMode.Interactive,
            requestedName = IMAGE_GENERATE_TOOL_NAME,
            canonicalName = IMAGE_GENERATE_TOOL_NAME,
            requestId = "r1",
        )

    private fun policy() = MediaGenerationPolicy(settingsDataStore, secrets, fakeBackendAllowed = true)

    private fun registry(
        fake: ImageBackend = backend,
        videoJobScheduler: VideoJobScheduler = VideoJobScheduler { _, _, _, _ -> },
    ): ToolRegistry =
        ToolRegistry(
            tools =
                mediaToolEntries(
                    MediaToolDependencies(
                        policy = policy(),
                        attachmentRepository = attachments,
                        mediaStore = GeneratedMediaStore(attachments.store, imageThumbnailWriter = { _, _ -> }),
                        openAiImageBackend = backend,
                        fakeImageBackend = fake,
                        client = OpenAiMediaClient(),
                        videoJobScheduler = videoJobScheduler,
                    ),
                ),
        )

    private fun runner(anthropic: ModelProvider? = null): AgentRunner {
        val toolRegistry = registry()
        return AgentRunner(
            providerRegistry =
                if (anthropic == null) buildTestProviderRegistry() else buildTestProviderRegistry(anthropicProvider = anthropic),
            settingsDataStore = settingsDataStore,
            messageRepository = messages,
            skillManager =
                createTestSkillManager(
                    application = application,
                    skillRepository = SkillRepository(database.skillRecordDao()),
                    toolDescriptor = toolRegistry::findDescriptor,
                ),
            toolRegistry = toolRegistry,
            sessionLaneCoordinator = SessionLaneCoordinator(),
            promptAssembler = PromptAssembler(),
            attachmentRepository = attachments,
            mediaGenerationPolicy = policy(),
        )
    }

    private suspend fun storeUserImage(
        id: String,
        session: String,
    ): StagedAttachment {
        val relativePath = attachments.store.relativePathFor(session, id)
        attachments.file(relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(testImageBytes(8, 8, "jpg"))
        }
        val staged = StagedAttachment(id, session, "$id.jpg", relativePath, "image/jpeg", 100, 8, 8)
        val message = messages.addMessage(sessionId = session, role = MessageRole.User, content = "photo")
        attachments.attachToMessage(message.id, session, listOf(staged))
        return staged
    }
}
