package ai.androidclaw.data.repository

internal const val SQLITE_LIKE_SEARCH_QUERY_MAX_CHARS = 500

internal fun String.toSqlLikeContainsPatternOrNull(): String? {
    val trimmed = trim()
    if (trimmed.isBlank() || trimmed.length > SQLITE_LIKE_SEARCH_QUERY_MAX_CHARS) {
        return null
    }
    return buildString(capacity = trimmed.length + 2) {
        append('%')
        trimmed.forEach { char ->
            if (char == SQLITE_LIKE_ESCAPE_CHAR || char == '%' || char == '_') {
                append(SQLITE_LIKE_ESCAPE_CHAR)
            }
            append(char)
        }
        append('%')
    }
}

private const val SQLITE_LIKE_ESCAPE_CHAR = '\\'
