package ai.androidclaw.runtime.media

import ai.androidclaw.R
import ai.androidclaw.data.model.AttachmentKind
import ai.androidclaw.data.model.MessageRole
import ai.androidclaw.data.repository.AttachmentRepository
import ai.androidclaw.data.repository.MessageRepository
import ai.androidclaw.data.repository.SessionRepository
import ai.androidclaw.runtime.orchestrator.SessionLaneCoordinator
import ai.androidclaw.runtime.scheduler.TASK_RESULTS_NOTIFICATION_CHANNEL_ID
import ai.androidclaw.runtime.scheduler.ensureTaskNotificationChannels
import ai.androidclaw.ui.locale.AppLocaleController
import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Clock
import java.util.concurrent.TimeUnit

internal const val VIDEO_JOB_GIVE_UP_MILLIS = 30L * 60 * 1000
internal const val VIDEO_JOB_INITIAL_DELAY_SECONDS = 30L
internal const val VIDEO_JOB_BACKOFF_SECONDS = 20L
private const val VIDEO_PROMPT_MAX_CHARS = 200

/** Enqueues one unique, one-shot [VideoJobWorker] per OpenAI video job. Nothing runs without a job. */
fun interface VideoJobScheduler {
    fun enqueue(
        jobId: String,
        sessionId: String,
        prompt: String,
        startedAtMillis: Long,
    )
}

class WorkManagerVideoJobScheduler(
    private val workManager: () -> WorkManager,
) : VideoJobScheduler {
    override fun enqueue(
        jobId: String,
        sessionId: String,
        prompt: String,
        startedAtMillis: Long,
    ) {
        val request =
            OneTimeWorkRequestBuilder<VideoJobWorker>()
                .setInputData(
                    workDataOf(
                        VideoJobWorker.KEY_JOB_ID to jobId,
                        VideoJobWorker.KEY_SESSION_ID to sessionId,
                        VideoJobWorker.KEY_PROMPT to prompt.take(VIDEO_PROMPT_MAX_CHARS),
                        VideoJobWorker.KEY_STARTED_AT to startedAtMillis,
                    ),
                ).setInitialDelay(VIDEO_JOB_INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, VIDEO_JOB_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(VideoJobWorker.TAG)
                .build()
        workManager().enqueueUniqueWork("${VideoJobWorker.TAG}-$jobId", ExistingWorkPolicy.KEEP, request)
    }
}

fun interface VideoNotifier {
    fun notifyVideoFinished(
        jobId: String,
        ready: Boolean,
        body: String,
    )
}

/** Posts on the task results channel; silent when notifications are off. */
class AndroidVideoNotifier(
    private val context: Context,
) : VideoNotifier {
    @SuppressLint("MissingPermission")
    override fun notifyVideoFinished(
        jobId: String,
        ready: Boolean,
        body: String,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureTaskNotificationChannels(context)
        val title =
            AppLocaleController
                .localizedResources(context)
                .getString(if (ready) R.string.video_notification_ready else R.string.video_notification_failed)
        val launch =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
        val notification =
            NotificationCompat
                .Builder(context, TASK_RESULTS_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(if (ready) android.R.drawable.ic_media_play else android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .apply { launch?.let(::setContentIntent) }
                .build()
        NotificationManagerCompat.from(context).notify(jobId.hashCode(), notification)
    }
}

/**
 * Checks one OpenAI video job. Still running → retry (exponential backoff); completed → download
 * the mp4 (≤ 50 MB) into the session folder and add an assistant message with a `VIDEO`
 * attachment; failed or older than 30 minutes → add a short failure message.
 */
class VideoJobWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val policy: MediaGenerationPolicy,
    private val client: OpenAiMediaClient,
    private val mediaStore: GeneratedMediaStore,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val attachmentRepository: AttachmentRepository,
    private val sessionLaneCoordinator: SessionLaneCoordinator,
    private val notifier: VideoNotifier,
    private val clock: Clock = Clock.systemUTC(),
    /** Transcript text in the app language, resolved when the worker writes a message. */
    private val resources: () -> Resources = { AppLocaleController.localizedResources(appContext) },
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val prompt = inputData.getString(KEY_PROMPT).orEmpty()
        val startedAt = inputData.getLong(KEY_STARTED_AT, clock.millis())
        if (sessionRepository.getSession(sessionId) == null) return Result.success()
        if (clock.millis() - startedAt > VIDEO_JOB_GIVE_UP_MILLIS) {
            return finishFailed(jobId, sessionId, texts().gaveUp(jobId))
        }
        val credentials =
            policy.credentials()
                ?: return finishFailed(jobId, sessionId, texts().keyRemoved(jobId))
        return when (val outcome = client.retrieveVideo(credentials, jobId)) {
            is VideoOutcome.Failure ->
                if (outcome.httpStatus in setOf(400, 401, 403, 404)) {
                    finishFailed(jobId, sessionId, texts().jobFailed(jobId, outcome.message))
                } else {
                    Result.retry()
                }

            is VideoOutcome.Success ->
                when (outcome.job.status) {
                    VideoJobStatus.Completed -> download(credentials, outcome.job, sessionId, prompt)
                    VideoJobStatus.Failed ->
                        finishFailed(jobId, sessionId, texts().generationFailed(outcome.job.errorMessage))
                    else -> Result.retry()
                }
        }
    }

    private fun texts() = VideoJobTexts(resources())

    private suspend fun download(
        credentials: OpenAiMediaCredentials,
        job: VideoJob,
        sessionId: String,
        prompt: String,
    ): Result {
        val (attachmentId, file) = mediaStore.newVideoTarget(sessionId)
        return when (val outcome = client.downloadVideo(credentials, job.id, file)) {
            is DownloadOutcome.Failure ->
                if (outcome.errorCode == "TOO_LARGE" || outcome.httpStatus in setOf(401, 403, 404)) {
                    finishFailed(job.id, sessionId, texts().notSaved(job.id, outcome.message))
                } else {
                    Result.retry()
                }

            is DownloadOutcome.Success -> {
                val (staged, durationMs) = mediaStore.describeVideo(sessionId, attachmentId, file)
                val text = texts().ready(job.seconds, job.size, prompt)
                sessionLaneCoordinator.withLane(sessionId) {
                    val message = messageRepository.addMessage(sessionId = sessionId, role = MessageRole.Assistant, content = text)
                    attachmentRepository.attachToMessage(message.id, sessionId, listOf(staged), AttachmentKind.Video, durationMs)
                }
                notifier.notifyVideoFinished(job.id, ready = true, body = prompt.ifBlank { text })
                Result.success()
            }
        }
    }

    private suspend fun finishFailed(
        jobId: String,
        sessionId: String,
        text: String,
    ): Result {
        sessionLaneCoordinator.withLane(sessionId) {
            messageRepository.addMessage(sessionId = sessionId, role = MessageRole.Assistant, content = text)
        }
        notifier.notifyVideoFinished(jobId, ready = false, body = text)
        return Result.failure()
    }

    companion object {
        const val TAG = "video-job"
        const val KEY_JOB_ID = "jobId"
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_PROMPT = "prompt"
        const val KEY_STARTED_AT = "startedAt"
    }
}

/** Video job messages for the transcript and the notification body. API detail stays as received. */
internal class VideoJobTexts(
    private val resources: Resources,
) {
    fun ready(
        seconds: String?,
        size: String?,
        prompt: String,
    ): String {
        val details =
            listOfNotNull(
                seconds?.let { resources.getString(R.string.video_job_seconds, it) },
                size?.replace('x', '×'),
            ).joinToString(resources.getString(R.string.video_job_detail_separator))
        val head =
            if (details.isEmpty()) {
                resources.getString(R.string.video_job_ready)
            } else {
                resources.getString(R.string.video_job_ready_details, details)
            }
        return if (prompt.isBlank()) head else resources.getString(R.string.video_job_with_prompt, head, prompt)
    }

    fun gaveUp(jobId: String): String = resources.getString(R.string.video_job_gave_up, jobId)

    fun keyRemoved(jobId: String): String = resources.getString(R.string.video_job_key_removed, jobId)

    fun jobFailed(
        jobId: String,
        detail: String,
    ): String = resources.getString(R.string.video_job_failed, jobId, detail)

    fun generationFailed(reason: String?): String {
        val shown = reason ?: resources.getString(R.string.video_job_no_reason)
        return resources.getString(R.string.video_job_generation_failed, shown)
    }

    fun notSaved(
        jobId: String,
        detail: String,
    ): String = resources.getString(R.string.video_job_not_saved, jobId, detail)
}
