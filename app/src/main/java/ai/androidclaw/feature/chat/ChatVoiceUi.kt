package ai.androidclaw.feature.chat

import ai.androidclaw.R
import ai.androidclaw.ui.components.ClawIcon
import ai.androidclaw.ui.components.ClawIconButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Composer mic: shown while the text field is empty and a recognizer resolves. */
@Composable
internal fun ComposerMicButton(onClick: () -> Unit) {
    ClawIconButton(
        iconRes = R.drawable.ic_mic,
        contentDescription = stringResource(R.string.chat_action_voice_input),
        onClick = onClick,
        modifier = Modifier.testTag("chatMicButton"),
    )
}

/** Stop stays reachable while speaking, even when the message scrolled away. */
@Composable
internal fun ReadAloudBar(onStop: () -> Unit) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .testTag("chatReadAloudBar"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClawIcon(iconRes = R.drawable.ic_volume_up, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.chat_reading_aloud),
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = onStop, modifier = Modifier.testTag("chatStopReadingButton")) {
                Text(stringResource(R.string.chat_action_stop_reading))
            }
        }
    }
}

/** Per-assistant-message read-aloud action; turns into Stop while that message is spoken. */
@Composable
internal fun ReadAloudButton(
    speaking: Boolean,
    onClick: () -> Unit,
) {
    ClawIconButton(
        iconRes = if (speaking) R.drawable.ic_stop else R.drawable.ic_volume_up,
        contentDescription = stringResource(if (speaking) R.string.chat_action_stop_reading else R.string.chat_action_read_aloud),
        onClick = onClick,
        modifier = Modifier.testTag("chatReadAloudButton"),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun VoiceRepliesMenuItem(
    checked: Boolean,
    onToggle: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_action_voice_replies)) },
        leadingIcon = { ClawIcon(iconRes = R.drawable.ic_volume_up, contentDescription = null) },
        trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = onToggle,
        modifier = Modifier.testTag("chatVoiceRepliesItem"),
    )
}
