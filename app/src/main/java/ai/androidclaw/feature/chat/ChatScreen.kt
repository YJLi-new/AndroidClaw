package ai.androidclaw.feature.chat

import ai.androidclaw.R
import ai.androidclaw.data.attachments.MAX_ATTACHMENTS_PER_MESSAGE
import ai.androidclaw.ui.components.ClawConfirmDialog
import ai.androidclaw.ui.components.ClawEmptyState
import ai.androidclaw.ui.components.ClawIcon
import ai.androidclaw.ui.components.ClawIconButton
import ai.androidclaw.ui.components.ClawNotice
import ai.androidclaw.ui.components.NoticeTone
import ai.androidclaw.ui.text.UiText
import ai.androidclaw.ui.text.asString
import ai.androidclaw.ui.text.resolve
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val SLASH_SUGGESTION_LIMIT = 6

private sealed interface ChatDialog {
    data class Rename(
        val session: ChatSessionUi,
    ) : ChatDialog

    data class Delete(
        val session: ChatSessionUi,
    ) : ChatDialog

    data object Usage : ChatDialog

    data object Search : ChatDialog

    data object Share : ChatDialog
}

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenProviderSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<ChatDialog?>(null) }
    var pendingExport by remember { mutableStateOf<ChatExportFileRequest?>(null) }
    var viewingAttachment by remember { mutableStateOf<ChatAttachmentUi?>(null) }
    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val payload = pendingExport
            pendingExport = null
            if (payload == null) return@rememberLauncherForActivityResult
            val uri = result.data?.data
            if (result.resultCode != Activity.RESULT_OK || uri == null) {
                viewModel.onExportCancelled()
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { writeExportPayload(context, uri, payload, viewModel) }
                }.onSuccess {
                    viewModel.onExternalActionCompleted(UiText.res(R.string.chat_notice_export_saved, payload.fileName))
                }.onFailure { error ->
                    viewModel.onExternalActionFailed(
                        UiText.res(R.string.chat_error_export_failed, error.message ?: payload.fileName),
                    )
                }
            }
        }

    LaunchedEffect(viewModel) {
        viewModel.actions.collect { action ->
            when (action) {
                is ChatExternalAction.ExportDocument -> {
                    pendingExport = action.payload
                    exportLauncher.launch(createExportDocumentIntent(action.payload))
                }

                is ChatExternalAction.ShareText -> launchShareText(context, action)

                is ChatExternalAction.ShareFile ->
                    runCatching {
                        val uri = withContext(Dispatchers.IO) { writeShareFile(context, action.payload, viewModel) }
                        launchShareFile(context, action.payload, uri)
                    }.onFailure { error ->
                        viewModel.onExternalActionFailed(
                            UiText.res(R.string.chat_error_share_failed, error.message ?: action.payload.fileName),
                        )
                    }
            }
        }
    }

    LaunchedEffect(state.noticeMessage) {
        val notice = state.noticeMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice.resolve(context))
        viewModel.clearNotice()
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    val defaultNewSessionTitle = stringResource(R.string.chat_new_session_default_title, state.sessions.size + 1)
    val copiedNotice = stringResource(R.string.chat_notice_copied)
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ChatSessionDrawer(
                sessions = state.sessions,
                archivedSessions = state.archivedSessions,
                showArchived = state.showArchivedSessions,
                enabled = !state.isRunning,
                actions =
                    SessionDrawerActions(
                        onNewSession = {
                            viewModel.createNewSession(defaultNewSessionTitle)
                            scope.launch { drawerState.close() }
                        },
                        onSelect = { session ->
                            viewModel.switchSession(session.id)
                            scope.launch { drawerState.close() }
                        },
                        onRename = { session -> dialog = ChatDialog.Rename(session) },
                        onArchive = { session -> viewModel.archiveSession(session.id) },
                        onRestore = { session -> viewModel.restoreSession(session.id) },
                        onDelete = { session -> dialog = ChatDialog.Delete(session) },
                        onToggleArchived = { viewModel.setShowArchivedSessions(!state.showArchivedSessions) },
                    ),
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.testTag("chatScreen"),
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                ChatTopBar(
                    state = state,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onRename = {
                        state.sessions.firstOrNull { it.isSelected }?.let { dialog = ChatDialog.Rename(it) }
                    },
                    onSearch = { dialog = ChatDialog.Search },
                    onShare = { dialog = ChatDialog.Share },
                    onUsage = { dialog = ChatDialog.Usage },
                    onCompact = viewModel::compactCurrentSession,
                    onNewSession = { viewModel.createNewSession(defaultNewSessionTitle) },
                    onArchive = viewModel::archiveCurrentSession,
                )
            },
        ) { padding ->
            ChatContent(
                state = state,
                viewModel = viewModel,
                contentPadding = padding,
                onOpenProviderSettings = onOpenProviderSettings,
                onCopied = { scope.launch { snackbarHostState.showSnackbar(copiedNotice) } },
                onOpenAttachment = { attachment -> viewingAttachment = attachment },
            )
        }
    }

    viewingAttachment?.let { attachment ->
        ChatAttachmentViewer(attachment = attachment, onDismiss = { viewingAttachment = null })
    }

    when (val current = dialog) {
        is ChatDialog.Rename ->
            RenameSessionDialog(
                initialTitle = current.session.title,
                onConfirm = { title ->
                    viewModel.renameSession(current.session.id, title)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )

        is ChatDialog.Delete ->
            ClawConfirmDialog(
                title = stringResource(R.string.chat_delete_title),
                text = stringResource(R.string.chat_delete_body, current.session.title),
                confirmLabel = stringResource(R.string.action_delete),
                destructive = true,
                onConfirm = {
                    viewModel.deleteSession(current.session.id)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )

        ChatDialog.Usage ->
            UsageDialog(
                usage = state.sessionUsage,
                sessionSummary = state.sessionSummary,
                onDismiss = { dialog = null },
            )

        ChatDialog.Search ->
            SearchSheet(
                state = state,
                onQueryChanged = viewModel::onSearchQueryChanged,
                onRunSearch = viewModel::runSearch,
                onClear = viewModel::clearSearch,
                onOpenResult = { result ->
                    viewModel.openSearchResult(result)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )

        ChatDialog.Share ->
            ShareSheet(
                onShareText = {
                    dialog = null
                    viewModel.shareCurrentSessionAsText()
                },
                onShareFile = {
                    dialog = null
                    viewModel.shareCurrentSessionAsFile(ChatExportFormat.Markdown)
                },
                onExport = { format ->
                    dialog = null
                    viewModel.exportCurrentSession(format)
                },
                onDismiss = { dialog = null },
            )

        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    state: ChatUiState,
    onOpenDrawer: () -> Unit,
    onRename: () -> Unit,
    onSearch: () -> Unit,
    onShare: () -> Unit,
    onUsage: () -> Unit,
    onCompact: () -> Unit,
    onNewSession: () -> Unit,
    onArchive: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val idle = !state.isRunning
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        navigationIcon = {
            ClawIconButton(
                iconRes = R.drawable.ic_menu,
                contentDescription = stringResource(R.string.chat_action_open_sessions),
                onClick = onOpenDrawer,
                modifier = Modifier.testTag("chatDrawerButton"),
            )
        },
        title = {
            Column(
                modifier =
                    Modifier
                        .clickable(
                            enabled = idle && state.currentSessionId.isNotBlank(),
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.chat_action_rename_session),
                            onClick = onRename,
                        ).semantics { heading() }
                        .testTag("chatHeading"),
            ) {
                Text(
                    text = state.sessionTitle.ifBlank { stringResource(R.string.chat_loading_session) }.let { sessionDisplayTitle(it, state.isMainSession) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        when {
                            state.isRunning -> state.activeTurnStage?.asString() ?: stringResource(R.string.chat_stage_waiting)
                            state.isFakeProvider -> stringResource(R.string.chat_subtitle_demo)
                            state.isMainSession -> stringResource(R.string.chat_subtitle_main)
                            else -> stringResource(R.string.chat_subtitle_session)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            ClawIconButton(
                iconRes = R.drawable.ic_search,
                contentDescription = stringResource(R.string.chat_action_search),
                onClick = onSearch,
                enabled = idle,
            )
            Box {
                ClawIconButton(
                    iconRes = R.drawable.ic_more_vert,
                    contentDescription = stringResource(R.string.action_more_options),
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("chatOverflowButton"),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    ChatMenuItem(R.string.chat_action_new_session, R.drawable.ic_add, idle) {
                        menuOpen = false
                        onNewSession()
                    }
                    ChatMenuItem(R.string.chat_action_rename_session, R.drawable.ic_edit, idle) {
                        menuOpen = false
                        onRename()
                    }
                    ChatMenuItem(R.string.chat_action_share, R.drawable.ic_share, idle && state.hasContent) {
                        menuOpen = false
                        onShare()
                    }
                    ChatMenuItem(R.string.chat_action_usage, R.drawable.ic_info, true) {
                        menuOpen = false
                        onUsage()
                    }
                    ChatMenuItem(R.string.chat_action_compact, R.drawable.ic_archive, idle && state.canCompactCurrentSession) {
                        menuOpen = false
                        onCompact()
                    }
                    if (state.canArchiveCurrentSession) {
                        ChatMenuItem(R.string.chat_action_archive_session, R.drawable.ic_archive, idle) {
                            menuOpen = false
                            onArchive()
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ChatMenuItem(
    labelRes: Int,
    iconRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        leadingIcon = { ClawIcon(iconRes = iconRes, contentDescription = null) },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun ChatContent(
    state: ChatUiState,
    viewModel: ChatViewModel,
    contentPadding: PaddingValues,
    onOpenProviderSettings: () -> Unit,
    onCopied: () -> Unit,
    onOpenAttachment: (ChatAttachmentUi) -> Unit,
) {
    var demoNoticeDismissed by rememberSaveable { mutableStateOf(false) }
    // System photo picker (no permission); androidx falls back to the document picker on old devices.
    val pickImages =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS_PER_MESSAGE),
        ) { uris -> viewModel.onImagesPicked(uris) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding(),
    ) {
        if (state.isFakeProvider && !demoNoticeDismissed) {
            ClawNotice(
                text = stringResource(R.string.chat_demo_notice),
                modifier =
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("chatProviderNotice"),
                tone = NoticeTone.Info,
                actionLabel = stringResource(R.string.chat_demo_notice_action),
                onAction = onOpenProviderSettings,
                onDismiss = { demoNoticeDismissed = true },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            ChatTranscript(
                state = state,
                viewModel = viewModel,
                onCopied = onCopied,
                onOpenAttachment = onOpenAttachment,
            )
        }
        SlashSuggestions(
            draft = viewModel.composerText,
            commands = state.slashCommands,
            onPick = { command -> viewModel.onDraftChanged("$command ") },
        )
        ComposerAttachmentChips(
            attachments = state.stagedAttachments,
            importing = state.isImportingAttachments,
            onRemove = viewModel::removeStagedAttachment,
        )
        ChatComposer(
            draft = viewModel.composerText,
            isRunning = state.isRunning,
            isCancelling = state.isCancelling,
            hasAttachments = state.stagedAttachments.isNotEmpty(),
            onDraftChanged = viewModel::onDraftChanged,
            onAttach = {
                if (!state.imagesEnabled) {
                    viewModel.onAttachUnavailable()
                } else {
                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            },
            onSend = viewModel::sendCurrentDraft,
            onStop = viewModel::cancelActiveTurn,
        )
    }
}

@Composable
private fun ChatTranscript(
    state: ChatUiState,
    viewModel: ChatViewModel,
    onCopied: () -> Unit,
    onOpenAttachment: (ChatAttachmentUi) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val locale = LocalConfiguration.current.locales[0]
    val timeFormatter =
        remember(locale) {
            DateTimeFormatter
                .ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(locale)
                .withZone(ZoneId.systemDefault())
        }
    val showPending = state.isRunning
    val rows = remember(state.items) { state.items.asReversed() }
    val newestKey = rows.firstOrNull()?.key

    // Reverse layout keeps the newest row anchored to the bottom. Follow new rows
    // only when the user was already reading the bottom of the transcript.
    LaunchedEffect(newestKey, showPending) {
        if (listState.firstVisibleItemIndex <= 2) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(state.highlightedMessageId, rows) {
        val target = state.highlightedMessageId ?: return@LaunchedEffect
        val index =
            rows.indexOfFirst { row ->
                when (row) {
                    is ChatItemUi.Message -> row.message.id == target
                    is ChatItemUi.Tool -> target in row.messageIds
                }
            }
        if (index >= 0) {
            val leading = (if (showPending) 1 else 0) + (if (state.errorMessage != null) 1 else 0)
            listState.animateScrollToItem(index + leading)
        }
    }

    if (!state.hasContent && !state.isRunning) {
        ClawEmptyState(
            iconRes = R.drawable.ic_nav_chat,
            title = stringResource(R.string.chat_empty_title),
            body =
                if (state.slashCommands.isEmpty()) {
                    stringResource(R.string.chat_empty_body)
                } else {
                    stringResource(R.string.chat_empty_body_with_commands)
                },
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("chatEmptyState"),
        )
        return
    }

    val onLongPress: (ChatMessageUi) -> Unit = { message ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.text)))
            onCopied()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("chatTranscript"),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            state.errorMessage?.let { error ->
                item(key = "turn-error", contentType = "error") {
                    ClawNotice(
                        text = error.asString(),
                        modifier =
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .testTag("chatErrorNotice"),
                        tone = NoticeTone.Error,
                        actionLabel = if (state.canRetryLastFailedTurn) stringResource(R.string.action_retry) else null,
                        onAction = if (state.canRetryLastFailedTurn) viewModel::retryLastFailedTurn else null,
                        onDismiss = viewModel::clearError,
                    )
                }
            }
            if (showPending) {
                item(key = "streaming", contentType = "streaming") {
                    ChatStreamingRow(
                        text = state.streamingAssistantText,
                        stage = state.activeTurnStage?.asString(),
                    )
                }
            }
            items(
                items = rows,
                key = { it.key },
                contentType = { if (it is ChatItemUi.Tool) "tool" else "message" },
            ) { row ->
                when (row) {
                    is ChatItemUi.Message ->
                        ChatMessageRow(
                            message = row.message,
                            highlighted = row.message.id == state.highlightedMessageId,
                            timeFormatter = timeFormatter,
                            onLongPress = onLongPress,
                            onOpenAttachment = onOpenAttachment,
                        )

                    is ChatItemUi.Tool -> ChatToolItem(item = row, running = state.isRunning, onOpenAttachment = onOpenAttachment)
                }
            }
            if (state.earlierMessageCount > 0) {
                item(key = "load-earlier", contentType = "control") {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = viewModel::loadEarlierMessages,
                            modifier = Modifier.testTag("chatLoadEarlier"),
                        ) {
                            Text(pluralStringResource(R.plurals.chat_load_earlier, state.earlierMessageCount, state.earlierMessageCount))
                        }
                    }
                }
            }
            if (state.compactedHiddenMessageCount > 0) {
                item(key = "compact-summary", contentType = "control") {
                    CompactedSummary(
                        state = state,
                        onToggle = viewModel::toggleCompactedHistory,
                    )
                }
            }
        }
        JumpToBottomButton(
            listState = listState,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
        )
    }
}

@Composable
private fun JumpToBottomButton(
    listState: LazyListState,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val show by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    if (show) {
        SmallFloatingActionButton(
            onClick = onClick,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            ClawIcon(
                iconRes = R.drawable.ic_keyboard_down,
                contentDescription = stringResource(R.string.chat_action_jump_to_latest),
            )
        }
    }
}

@Composable
private fun CompactedSummary(
    state: ChatUiState,
    onToggle: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.chat_compacted_title), style = MaterialTheme.typography.titleSmall)
            state.sessionSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                ChatRichText(text = summary, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text =
                    if (state.showCompactedHistory) {
                        pluralStringResource(R.plurals.chat_compacted_shown, state.compactedHiddenMessageCount, state.compactedHiddenMessageCount)
                    } else {
                        pluralStringResource(R.plurals.chat_compacted_hidden, state.compactedHiddenMessageCount, state.compactedHiddenMessageCount)
                    },
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onToggle, enabled = !state.isRunning) {
                Text(
                    stringResource(
                        if (state.showCompactedHistory) R.string.chat_compacted_hide else R.string.chat_compacted_show,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SlashSuggestions(
    draft: String,
    commands: List<SlashCommandUi>,
    onPick: (String) -> Unit,
) {
    if (!draft.startsWith("/") || draft.any(Char::isWhitespace)) return
    val matches =
        remember(draft, commands) {
            commands.filter { it.command.startsWith(draft, ignoreCase = true) }.take(SLASH_SUGGESTION_LIMIT)
        }
    if (matches.isEmpty()) return
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag("chatSlashSuggestions"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            matches.forEach { command ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onPick(command.command) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(command.command, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (command.description.isNotBlank()) {
                        Text(
                            text = command.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatComposer(
    draft: String,
    isRunning: Boolean,
    isCancelling: Boolean,
    hasAttachments: Boolean,
    onDraftChanged: (String) -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ClawIconButton(
            iconRes = R.drawable.ic_image,
            contentDescription = stringResource(R.string.chat_action_attach_image),
            onClick = onAttach,
            enabled = !isRunning,
            modifier = Modifier.testTag("chatAttachButton"),
        )
        TextField(
            value = draft,
            onValueChange = onDraftChanged,
            modifier =
                Modifier
                    .weight(1f)
                    .testTag("chatInput"),
            placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
            minLines = 1,
            maxLines = 6,
            shape = RoundedCornerShape(24.dp),
            colors =
                TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
        )
        if (isRunning) {
            FilledIconButton(
                onClick = onStop,
                enabled = !isCancelling,
                modifier = Modifier.testTag("chatStopButton"),
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) {
                ClawIcon(iconRes = R.drawable.ic_stop, contentDescription = stringResource(R.string.chat_action_stop))
            }
        } else {
            FilledIconButton(
                onClick = onSend,
                enabled = draft.isNotBlank() || hasAttachments,
                modifier = Modifier.testTag("chatSendButton"),
            ) {
                ClawIcon(iconRes = R.drawable.ic_send, contentDescription = stringResource(R.string.chat_action_send))
            }
        }
    }
}

private fun createExportDocumentIntent(payload: ChatExportFileRequest): Intent =
    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = payload.mimeType
        putExtra(Intent.EXTRA_TITLE, payload.fileName)
    }

private suspend fun writeExportPayload(
    context: Context,
    uri: Uri,
    payload: ChatExportFileRequest,
    viewModel: ChatViewModel,
) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
        requireNotNull(writer) { "Unable to open destination for ${payload.fileName}" }
        viewModel.writeExportFile(payload, writer)
    }
}

private fun launchShareText(
    context: Context,
    action: ChatExternalAction.ShareText,
) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, action.subject)
            putExtra(Intent.EXTRA_TEXT, action.text)
        }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.chat_share_chooser)))
}

private suspend fun writeShareFile(
    context: Context,
    payload: ChatExportFileRequest,
    viewModel: ChatViewModel,
): Uri {
    val exportDirectory = File(context.cacheDir, "chat-exports").apply { mkdirs() }
    val exportFile = File(exportDirectory, payload.fileName)
    exportFile.bufferedWriter().use { writer ->
        viewModel.writeExportFile(payload, writer)
    }
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.chat-export-provider",
        exportFile,
    )
}

private fun launchShareFile(
    context: Context,
    payload: ChatExportFileRequest,
    uri: Uri,
) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = payload.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, payload.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.chat_share_chooser)))
}
