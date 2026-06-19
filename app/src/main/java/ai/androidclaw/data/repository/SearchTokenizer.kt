package ai.androidclaw.data.repository

internal const val SEARCH_TOKEN_MAX_TOKENS = 32
internal const val SEARCH_TOKEN_MAX_LENGTH = 64

internal fun normalizeSearchText(text: String): String =
    text
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(separator = " ")
        .replace(Regex("\\s+"), " ")
        .trim()

internal fun tokenizeSearchText(text: String): Set<String> {
    val normalizedText = normalizeSearchText(text).lowercase()
    if (normalizedText.isBlank()) {
        return emptySet()
    }

    val tokens = linkedSetOf<String>()
    searchTokenRegex.findAll(normalizedText).forEach { match ->
        val rawToken = match.value.trim('_').take(SEARCH_TOKEN_MAX_LENGTH)
        if (rawToken.isBlank()) {
            return@forEach
        }
        if (rawToken.any(Char::isCompactScriptSearchChar)) {
            val compactChars = rawToken.filter(Char::isCompactScriptSearchChar)
            compactChars.forEach { tokens += it.toString() }
            compactChars.windowed(size = 2).forEach { tokens += it }
            rawToken
                .split(Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]+"))
                .map(String::trim)
                .filter { it.length >= 2 }
                .forEach { tokens += it.take(SEARCH_TOKEN_MAX_LENGTH) }
        } else if (rawToken.length >= 2) {
            tokens += rawToken
        }
        if (tokens.size >= SEARCH_TOKEN_MAX_TOKENS) {
            return tokens.take(SEARCH_TOKEN_MAX_TOKENS).toCollection(linkedSetOf())
        }
    }
    return tokens.take(SEARCH_TOKEN_MAX_TOKENS).toCollection(linkedSetOf())
}

internal fun minimumSearchTokenMatches(queryTokens: Set<String>): Int =
    when {
        queryTokens.isEmpty() -> 0
        queryTokens.size == 1 -> 1
        queryTokens.any { token -> token.length == 1 } -> 2.coerceAtMost(queryTokens.size)
        else -> queryTokens.size.coerceAtMost(4)
    }

internal fun String.toSqliteLikeEscapedLiteral(): String =
    buildString {
        this@toSqliteLikeEscapedLiteral.forEach { char ->
            when (char) {
                '\\', '%', '_' -> {
                    append('\\')
                    append(char)
                }
                else -> append(char)
            }
        }
    }

private val searchTokenRegex = Regex("[\\p{L}\\p{N}_]+")

private fun Char.isCompactScriptSearchChar(): Boolean {
    val block = Character.UnicodeBlock.of(this)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
        block == Character.UnicodeBlock.HANGUL_JAMO ||
        block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
}
