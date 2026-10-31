package ai.androidclaw.feature.chat

import ai.androidclaw.data.repository.SESSION_TITLE_MAX_CHARS

/** Rename input shares the repository title budget. */
internal const val CHAT_SESSION_RENAME_MAX_CHARS = SESSION_TITLE_MAX_CHARS

internal data class BoundedChatRenameInput(
    val value: String,
    val wasTruncated: Boolean,
)

internal fun boundChatRenameInput(value: String): BoundedChatRenameInput {
    val boundedValue = value.take(CHAT_SESSION_RENAME_MAX_CHARS)
    return BoundedChatRenameInput(
        value = boundedValue,
        wasTruncated = value.length > boundedValue.length,
    )
}
