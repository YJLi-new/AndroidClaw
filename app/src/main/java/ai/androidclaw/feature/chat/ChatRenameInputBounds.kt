package ai.androidclaw.feature.chat

import ai.androidclaw.data.repository.SESSION_TITLE_MAX_CHARS

internal const val CHAT_SESSION_RENAME_TRUNCATED_NOTICE =
    "Session title truncated by AndroidClaw to keep the chat screen responsive."

internal data class BoundedChatRenameInput(
    val value: String,
    val wasTruncated: Boolean,
)

internal fun boundChatRenameInput(value: String): BoundedChatRenameInput {
    val boundedValue = value.take(SESSION_TITLE_MAX_CHARS)
    return BoundedChatRenameInput(
        value = boundedValue,
        wasTruncated = value.length > boundedValue.length,
    )
}
