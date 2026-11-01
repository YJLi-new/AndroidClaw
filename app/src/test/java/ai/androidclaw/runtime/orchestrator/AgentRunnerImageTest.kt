package ai.androidclaw.runtime.orchestrator

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.attachments.AttachmentStore
import ai.androidclaw.data.attachments.StagedAttachment
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.AttachmentRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.data.repository.SkillRepository
import ai.androidclaw.runtime.providers.ModelMessage
import ai.androidclaw.runtime.providers.ModelProvider
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelResponse
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.providers.marker
import ai.androidclaw.runtime.skills.createTestSkillManager
import ai.androidclaw.runtime.tools.ToolRegistry
import ai.androidclaw.testutil.buildTestProviderRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** An image turn end to end: staged chip → persisted attachment → bounded request → FakeProvider reply. */
@RunWith(AndroidJUnit4::class)
class AgentRunnerImageTest {
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var root: File
    private lateinit var attachments: AttachmentRepository
    private lateinit var messages: MessageRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var sessionId: String

    @Before
    fun setUp() =
        runTest {
            application = ApplicationProvider.getApplicationContext()
            database = buildTestDatabase(application)
            root = Files.createTempDirectory("runner-images").toFile()
            attachments = AttachmentRepository(database.messageAttachmentDao(), AttachmentStore(root))
            messages = MessageRepository(database.messageDao(), attachments)
            settingsDataStore = SettingsDataStore(application)
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
            sessionId = SessionRepository(database.sessionDao(), attachments).createSession("Images").id
        }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun `image turn persists the attachment and the fake provider sees it`() =
        runTest {
            val result =
                runner().runInteractiveTurn(
                    AgentTurnRequest(sessionId = sessionId, userMessage = "What is this?", attachments = listOf(staged("img1"))),
                )
            assertEquals("Reply: What is this?\nImages: 1 (1568×1176)", result.assistantMessage)
            val user = messages.getMessages(sessionId).first { it.role == MessageRole.User }
            assertEquals(listOf("img1"), user.attachments.map { it.id })
        }

    @Test
    fun `image-only turn is accepted`() =
        runTest {
            val result =
                runner().runInteractiveTurn(
                    AgentTurnRequest(sessionId = sessionId, userMessage = "", attachments = listOf(staged("img2"))),
                )
            assertEquals("Reply: Images: 1 (1568×1176)", result.assistantMessage)
        }

    @Test
    fun `text-only provider gets markers and no image bytes`() =
        runTest {
            var seen: List<ModelMessage> = emptyList()
            val recorder =
                object : ModelProvider {
                    override val id: String = "fake"

                    override suspend fun generate(request: ModelRequest): ModelResponse {
                        seen = request.messageHistory
                        return ModelResponse(text = "ok")
                    }
                }
            // DeepSeek has no vision by default and the switch is off.
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot(providerType = ProviderType.DeepSeek))
            val runner = runner(provider = recorder, deepSeek = true)
            runner.runInteractiveTurn(AgentTurnRequest(sessionId = sessionId, userMessage = "Hi", attachments = listOf(staged("img3"))))
            val attachment = seen.last { it.attachments.isNotEmpty() }.attachments.single()
            assertNull(attachment.file)
            assertEquals("[attachment img3: img3.jpg 1568×1176]", attachment.marker())
            settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot())
        }

    private fun runner(
        provider: ModelProvider? = null,
        deepSeek: Boolean = false,
    ): AgentRunner {
        val toolRegistry = ToolRegistry(tools = emptyList())
        val registry =
            if (provider == null) {
                buildTestProviderRegistry()
            } else if (deepSeek) {
                ProviderRegistry(
                    buildTestProviderRegistry().descriptors().map { descriptor ->
                        ProviderRegistry.RegisteredProviderEntry(
                            type = descriptor.type,
                            displayName = descriptor.displayName,
                            provider = provider,
                        )
                    },
                )
            } else {
                buildTestProviderRegistry(fakeProvider = provider)
            }
        return AgentRunner(
            providerRegistry = registry,
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
        )
    }

    private fun staged(id: String): StagedAttachment {
        val relativePath = attachments.store.relativePathFor(sessionId, id)
        attachments.file(relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        assertTrue(attachments.file(relativePath).isFile)
        return StagedAttachment(id, sessionId, "$id.jpg", relativePath, "image/jpeg", 3, 1568, 1176)
    }
}
