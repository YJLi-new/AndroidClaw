package ai.androidclaw.feature.chat

import ai.androidclaw.R
import ai.androidclaw.ui.components.ClawIcon
import ai.androidclaw.ui.components.ClawIconButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class SessionDrawerActions(
    val onNewSession: () -> Unit,
    val onSelect: (ChatSessionUi) -> Unit,
    val onRename: (ChatSessionUi) -> Unit,
    val onArchive: (ChatSessionUi) -> Unit,
    val onRestore: (ChatSessionUi) -> Unit,
    val onDelete: (ChatSessionUi) -> Unit,
    val onToggleArchived: () -> Unit,
)

@Composable
internal fun ChatSessionDrawer(
    sessions: List<ChatSessionUi>,
    archivedSessions: List<ChatSessionUi>,
    showArchived: Boolean,
    enabled: Boolean,
    actions: SessionDrawerActions,
) {
    val mainSession = sessions.firstOrNull { it.isMain }
    val otherSessions = sessions.filterNot { it.isMain }
    ModalDrawerSheet(
        modifier =
            Modifier
                .widthIn(max = 320.dp)
                .fillMaxHeight()
                .testTag("chatSessionDrawer"),
    ) {
        Text(
            text = stringResource(R.string.chat_drawer_title),
            modifier =
                Modifier
                    .padding(start = 28.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)
                    .semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        NavigationDrawerItem(
            modifier =
                Modifier
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                    .testTag("chatNewSessionButton"),
            label = { Text(stringResource(R.string.chat_action_new_session)) },
            icon = { ClawIcon(iconRes = R.drawable.ic_add, contentDescription = null) },
            selected = false,
            onClick = { if (enabled) actions.onNewSession() },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            mainSession?.let { main ->
                item(key = "main-${main.id}") {
                    SessionDrawerItem(
                        session = main,
                        label = main.title,
                        badge = stringResource(R.string.chat_session_main_badge),
                        enabled = enabled,
                        actions = actions,
                        archived = false,
                    )
                }
            }
            items(otherSessions, key = { it.id }) { session ->
                SessionDrawerItem(
                    session = session,
                    label = session.title,
                    badge = null,
                    enabled = enabled,
                    actions = actions,
                    archived = false,
                )
            }
            item(key = "archived-toggle") {
                NavigationDrawerItem(
                    modifier =
                        Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag("chatArchivedToggle"),
                    label = { Text(stringResource(R.string.chat_drawer_archived)) },
                    icon = { ClawIcon(iconRes = R.drawable.ic_archive, contentDescription = null) },
                    badge = {
                        ClawIcon(
                            iconRes = if (showArchived) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                            contentDescription = null,
                        )
                    },
                    selected = false,
                    onClick = actions.onToggleArchived,
                )
            }
            if (showArchived) {
                if (archivedSessions.isEmpty()) {
                    item(key = "archived-empty") {
                        Text(
                            text = stringResource(R.string.chat_drawer_archived_empty),
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(archivedSessions, key = { "archived-${it.id}" }) { session ->
                    SessionDrawerItem(
                        session = session,
                        label = session.title,
                        badge = null,
                        enabled = enabled,
                        actions = actions,
                        archived = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionDrawerItem(
    session: ChatSessionUi,
    label: String,
    badge: String?,
    enabled: Boolean,
    actions: SessionDrawerActions,
    archived: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    NavigationDrawerItem(
        modifier =
            Modifier
                .padding(NavigationDrawerItemDefaults.ItemPadding)
                .testTag("chatSessionItem-${session.id}"),
        label = {
            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        icon = {
            ClawIcon(
                iconRes = if (archived) R.drawable.ic_archive else R.drawable.ic_nav_chat,
                contentDescription = null,
            )
        },
        badge = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge != null) {
                    Text(badge, style = MaterialTheme.typography.labelSmall)
                }
                Box {
                    ClawIconButton(
                        iconRes = R.drawable.ic_more_vert,
                        contentDescription = stringResource(R.string.chat_session_actions, session.title),
                        onClick = { menuOpen = true },
                        enabled = enabled,
                    )
                    SessionMenu(
                        session = session,
                        archived = archived,
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        actions = actions,
                    )
                }
            }
        },
        selected = session.isSelected,
        onClick = {
            if (enabled && !archived) actions.onSelect(session)
        },
    )
}

@Composable
private fun SessionMenu(
    session: ChatSessionUi,
    archived: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: SessionDrawerActions,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (archived) {
            SessionMenuItem(R.string.chat_action_restore_session, R.drawable.ic_unarchive) {
                onDismiss()
                actions.onRestore(session)
            }
        } else {
            SessionMenuItem(R.string.chat_action_rename_session, R.drawable.ic_edit) {
                onDismiss()
                actions.onRename(session)
            }
            if (!session.isMain) {
                SessionMenuItem(R.string.chat_action_archive_session, R.drawable.ic_archive) {
                    onDismiss()
                    actions.onArchive(session)
                }
            }
        }
        if (!session.isMain) {
            SessionMenuItem(R.string.chat_action_delete_session, R.drawable.ic_delete) {
                onDismiss()
                actions.onDelete(session)
            }
        }
    }
}

@Composable
private fun SessionMenuItem(
    labelRes: Int,
    iconRes: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        leadingIcon = { ClawIcon(iconRes = iconRes, contentDescription = null) },
        onClick = onClick,
    )
}
