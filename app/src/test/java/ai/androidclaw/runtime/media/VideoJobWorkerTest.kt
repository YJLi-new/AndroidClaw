package ai.androidclaw.runtime.media

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.attachments.AttachmentStore
import ai.androidclaw.data.db.AndroidClawDatabase
import ai.androidclaw.data.db.buildTestDatabase
import ai.androidclaw.data.model.AttachmentKind
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.AttachmentRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.orchestrator.SessionLaneCoordinator
import ai.androidclaw.testutil.InMemoryProviderSecretStore
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** VideoJobWorker contracts: in progress → retry, completed → download + VIDEO message, failed, timeout. */
@RunWith(AndroidJUnit4::class)
class VideoJobWorkerTest {
    private lateinit var application: android.app.Application
    private lateinit var database: AndroidClawDatabase
    private lateinit var root: File
    private lateinit var server: MockWebServer
    private lateinit var attachments: AttachmentRepository
    private lateinit var messages: MessageRepository
    private lateinit var sessions: SessionRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var sessionId: String
    private val secrets = InMemoryProviderSecretStore(mapOf(ProviderType.OpenAi to "sk-test"))
    private val notifications = mutableListOf<Pair<String, Boolean>>()
    private val now = Instant.parse("2026-11-01T10:00:00Z")

    @Before
    fun setUp() =
        runBlocking {
            application = ApplicationProvider.getApplicationContext()
            database = buildTestDatabase(application)
            root = Files.createTempDirectory("video-worker").toFile()
            server = MockWebServer().also { it.start() }
            attachments = AttachmentRepository(database.messageAttachmentDao(), AttachmentStore(root))
            messages = MessageRepository(database.messageDao(), attachments)
            sessions = SessionRepository(database.sessionDao(), attachments)
            settingsDataStore = SettingsDataStore(application)
            val snapshot = ProviderSettingsSnapshot()
            settingsDataStore.saveProviderSettings(
                snapshot.withEndpointSettings(
                    ProviderType.OpenAi,
                    snapshot.endpointSettings(ProviderType.OpenAi).copy(baseUrl = server.url("/v1").toString()),
                ),
            )
            sessionId = sessions.createSession("Video").id
        }

    @After
    fun tearDown() {
        runBlocking { settingsDataStore.saveProviderSettings(ProviderSettingsSnapshot()) }
        server.shutdown()
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun `in progress job retries without adding messages`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(videoJson("video_1", "in_progress")))

            val result = worker(startedAt = now.minusSeconds(60)).doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            assertEquals("/v1/videos/video_1", server.takeRequest().path)
            assertTrue(messages.getMessages(sessionId).isEmpty())
            assertTrue(notifications.isEmpty())
        }

    @Test
    fun `completed job downloads the mp4 and adds a VIDEO reply`() =
        runBlocking {
            val mp4 = ByteArray(4_096) { it.toByte() }
            server.enqueue(MockResponse().setBody(videoJson("video_1", "completed")))
            server.enqueue(MockResponse().setBody(Buffer().write(mp4)).setHeader("Content-Type", "video/mp4"))

            val result = worker(startedAt = now.minusSeconds(120)).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            server.takeRequest()
            assertEquals("/v1/videos/video_1/content", server.takeRequest().path)
            val reply = messages.getMessages(sessionId).single()
            assertEquals(MessageRole.Assistant, reply.role)
            assertEquals("Video ready (4 s, 720×1280): a cat surfing", reply.content)
            val video = reply.attachments.single()
            assertEquals(AttachmentKind.Video, video.kind)
            assertEquals("video/mp4", video.mimeType)
            assertEquals(4_000L, video.durationMs)
            assertEquals(720 to 1280, video.width to video.height)
            assertEquals(4_096L, attachments.file(video.relativePath).length())
            assertEquals(listOf("video_1" to true), notifications)
        }

    @Test
    fun `failed job adds a failure message and stops`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(videoJson("video_1", "failed", ""","error":{"message":"blocked by moderation"}""")))

            val result = worker(startedAt = now.minusSeconds(60)).doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
            assertEquals("Video generation failed: blocked by moderation.", messages.getMessages(sessionId).single().content)
            assertEquals(listOf("video_1" to false), notifications)
        }

    @Test
    fun `job older than 30 minutes gives up without a network call`() =
        runBlocking {
            val result = worker(startedAt = now.minusSeconds(31 * 60)).doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
            assertEquals(0, server.requestCount)
            assertTrue(
                messages
                    .getMessages(sessionId)
                    .single()
                    .content
                    .contains("gave up after 30 minutes"),
            )
        }

    @Test
    fun `server errors retry and a deleted session ends quietly`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(ListenableWorker.Result.retry(), worker(startedAt = now.minusSeconds(60)).doWork())

            sessions.deleteSession(sessionId)
            assertEquals(ListenableWorker.Result.success(), worker(startedAt = now.minusSeconds(60)).doWork())
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `scheduler enqueues one unique network-bound job per video`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(application, Configuration.Builder().build())
        val workManager = WorkManager.getInstance(application)
        val scheduler = WorkManagerVideoJobScheduler { workManager }

        scheduler.enqueue("video_9", sessionId, "p", now.toEpochMilli())
        scheduler.enqueue("video_9", sessionId, "p", now.toEpochMilli())

        val infos = workManager.getWorkInfosForUniqueWork("${VideoJobWorker.TAG}-video_9").get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertTrue(VideoJobWorker.TAG in infos.single().tags)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
    }

    private fun worker(startedAt: Instant): VideoJobWorker {
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    VideoJobWorker(
                        appContext = appContext,
                        workerParams = workerParameters,
                        policy = MediaGenerationPolicy(settingsDataStore, secrets, fakeBackendAllowed = false),
                        client = OpenAiMediaClient(),
                        mediaStore =
                            GeneratedMediaStore(
                                attachments.store,
                                videoInspector = { _, _ -> VideoFileInfo(720, 1280, 4_000L) },
                            ),
                        sessionRepository = sessions,
                        messageRepository = messages,
                        attachmentRepository = attachments,
                        sessionLaneCoordinator = SessionLaneCoordinator(),
                        notifier = { jobId, ready, _ -> notifications += jobId to ready },
                        clock = clock,
                    )
            }
        return TestListenableWorkerBuilder<VideoJobWorker>(application)
            .setInputData(
                workDataOf(
                    VideoJobWorker.KEY_JOB_ID to "video_1",
                    VideoJobWorker.KEY_SESSION_ID to sessionId,
                    VideoJobWorker.KEY_PROMPT to "a cat surfing",
                    VideoJobWorker.KEY_STARTED_AT to startedAt.toEpochMilli(),
                ),
            ).setWorkerFactory(factory)
            .build()
    }
}
