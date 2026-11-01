package ai.androidclaw.feature.chat

import ai.androidclaw.R
import ai.androidclaw.data.attachments.ImageImportMath
import ai.androidclaw.data.attachments.PixelSize
import ai.androidclaw.ui.components.ClawIcon
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One image shown in the transcript, a composer chip or the viewer. */
data class ChatAttachmentUi(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val file: File,
    val thumbnailFile: File,
)

private const val THUMBNAIL_CACHE_BYTES = 8 * 1024 * 1024

/** Bounded in-memory cache of decoded 256 px thumbnails, keyed by file path. */
private object ChatThumbnailCache : LruCache<String, ImageBitmap>(THUMBNAIL_CACHE_BYTES) {
    override fun sizeOf(
        key: String,
        value: ImageBitmap,
    ): Int = value.width * value.height * 4
}

private fun loadThumbnail(attachment: ChatAttachmentUi): ImageBitmap? {
    val source = attachment.thumbnailFile.takeIf(File::isFile) ?: attachment.file
    val key = source.path
    ChatThumbnailCache.get(key)?.let { return it }
    val bitmap = decodeSampled(source, IMPORT_PREVIEW_LONG_SIDE_PX) ?: return null
    return bitmap.asImageBitmap().also { ChatThumbnailCache.put(key, it) }
}

private const val IMPORT_PREVIEW_LONG_SIDE_PX = 256

/** Decodes [file] with a power-of-two sample so the long side stays near [maxLongSide]. */
private fun decodeSampled(
    file: File,
    maxLongSide: Int,
): android.graphics.Bitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = ImageImportMath.sampleSizeFor(PixelSize(bounds.outWidth, bounds.outHeight), maxLongSide)
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun AttachmentThumbnail(
    attachment: ChatAttachmentUi,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState(initialValue = ChatThumbnailCache.get(attachment.thumbnailFile.path), attachment.id) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { loadThumbnail(attachment) }
        }
    }
    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = attachment.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Thumbnail grid above a user message; tap opens the viewer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatAttachmentGrid(
    attachments: List<ChatAttachmentUi>,
    onOpen: (ChatAttachmentUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    val tileSize = if (attachments.size == 1) 160.dp else 96.dp
    FlowRow(
        modifier = modifier.testTag("chatAttachmentGrid"),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            val label = stringResource(R.string.chat_attachment_open, attachment.displayName)
            AttachmentThumbnail(
                attachment = attachment,
                size = tileSize,
                modifier =
                    Modifier
                        .clickable(role = Role.Image, onClickLabel = label) { onOpen(attachment) }
                        .testTag("chatAttachment-${attachment.id}"),
            )
        }
    }
}

/** Removable thumbnail chips shown above the composer before sending. */
@Composable
internal fun ComposerAttachmentChips(
    attachments: List<ChatAttachmentUi>,
    importing: Boolean,
    onRemove: (String) -> Unit,
) {
    if (attachments.isEmpty() && !importing) return
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 6.dp)
                .testTag("chatComposerAttachments"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { attachment ->
            Box {
                AttachmentThumbnail(attachment = attachment, size = 64.dp)
                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(24.dp)
                            .testTag("chatRemoveAttachment-${attachment.id}"),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    onClick = { onRemove(attachment.id) },
                ) {
                    ClawIcon(
                        iconRes = R.drawable.ic_close,
                        contentDescription = stringResource(R.string.chat_attachment_remove, attachment.displayName),
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
        }
        if (importing) {
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** Full-screen viewer: sampled to the screen size, with Save and Share. */
@Composable
internal fun ChatAttachmentViewer(
    attachment: ChatAttachmentUi,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var status by remember(attachment.id) { mutableStateOf<String?>(null) }
    val savedDocument = stringResource(R.string.chat_attachment_saved_document)
    val saveAsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(attachment.mimeType)) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                status =
                    runCatching { withContext(Dispatchers.IO) { copyToUri(context, attachment.file, uri) } }
                        .fold(
                            onSuccess = { savedDocument },
                            onFailure = { error -> resources.getString(R.string.chat_attachment_save_failed, error.message ?: "-") },
                        )
            }
        }
    val longSide =
        remember {
            val metrics = resources.displayMetrics
            maxOf(metrics.widthPixels, metrics.heightPixels)
        }
    val image by produceState<ImageBitmap?>(initialValue = null, attachment.id) {
        value = withContext(Dispatchers.IO) { decodeSampled(attachment.file, longSide)?.asImageBitmap() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .testTag("chatAttachmentViewer"),
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                image?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = attachment.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            status?.let { text ->
                Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("chatAttachmentViewerStatus"),
                )
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("chatAttachmentViewerClose")) {
                    Text(stringResource(R.string.chat_attachment_close), color = Color.White)
                }
                TextButton(
                    onClick = {
                        if (!attachment.file.isFile) {
                            status = resources.getString(R.string.chat_attachment_missing)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            scope.launch {
                                status =
                                    runCatching { withContext(Dispatchers.IO) { saveToPictures(context, attachment) } }
                                        .fold(
                                            onSuccess = { resources.getString(R.string.chat_attachment_saved) },
                                            onFailure = { error ->
                                                resources.getString(R.string.chat_attachment_save_failed, error.message ?: "-")
                                            },
                                        )
                            }
                        } else {
                            saveAsLauncher.launch(attachment.displayName)
                        }
                    },
                    modifier = Modifier.testTag("chatAttachmentViewerSave"),
                ) {
                    Text(stringResource(R.string.chat_attachment_save), color = Color.White)
                }
                TextButton(
                    onClick = {
                        if (attachment.file.isFile) {
                            shareAttachment(context, attachment)
                        } else {
                            status = resources.getString(R.string.chat_attachment_missing)
                        }
                    },
                    modifier = Modifier.testTag("chatAttachmentViewerShare"),
                ) {
                    Text(stringResource(R.string.chat_attachment_share), color = Color.White)
                }
            }
        }
    }
}

/** API 29+: MediaStore insert under Pictures/AndroidClaw; no storage permission needed. */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private fun saveToPictures(
    context: Context,
    attachment: ChatAttachmentUi,
) {
    val resolver = context.contentResolver
    val values =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, attachment.displayName)
            put(MediaStore.Images.Media.MIME_TYPE, attachment.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AndroidClaw")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    val uri =
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
    try {
        copyToUri(context, attachment.file, uri)
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}

private fun copyToUri(
    context: Context,
    file: File,
    uri: Uri,
) {
    val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot open destination")
    output.use { stream -> file.inputStream().use { input -> input.copyTo(stream) } }
}

private fun shareAttachment(
    context: Context,
    attachment: ChatAttachmentUi,
) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.chat-export-provider", attachment.file)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = attachment.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.chat_attachment_share_chooser)))
}
