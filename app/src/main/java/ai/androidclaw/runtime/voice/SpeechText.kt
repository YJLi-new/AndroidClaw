package ai.androidclaw.runtime.voice

import java.util.Locale

/** Below the platform limit (TextToSpeech.getMaxSpeechInputLength() is 4000). */
internal const val SPEECH_CHUNK_MAX_CHARS = 3_000

private val FENCE = Regex("""^\s*(```|~~~)""")
private val HEADING = Regex("""^\s{0,3}#{1,6}\s+""")
private val BLOCKQUOTE = Regex("""^\s*(>\s?)+""")
private val TASK_MARKER = Regex("""^\s*([-*+]|\d+[.)])\s+\[[ xX]]\s+""")
private val LIST_MARKER = Regex("""^\s*([-*+]|\d+[.)])\s+""")
private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")
private val IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")
private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val INLINE_CODE = Regex("""`+([^`]+)`+""")
private val BOLD = Regex("""(\*\*|__)(\S(?:.*?\S)?)\1""")
private val ITALIC_STAR = Regex("""(?<![\w*])\*(\S(?:.*?\S)?)\*(?![\w*])""")
private val ITALIC_UNDERSCORE = Regex("""(?<!\w)_(\S(?:.*?\S)?)_(?!\w)""")
private val STRIKE = Regex("""~~(.+?)~~""")
private val HTML_TAG = Regex("""</?[A-Za-z][^>]*>""")
private val BARE_URL = Regex("""https?://\S+""")
private val SPACES = Regex("""[ \t]+""")
private val SENTENCE_END = charArrayOf('\n', '.', '!', '?', ';', '。', '！', '？', '；')

/**
 * Plain text for read-aloud: markdown markers are removed, links keep their
 * label, bare URLs are dropped and each code block becomes [codeBlockPhrase].
 */
fun speechTextFromMarkdown(
    markdown: String,
    codeBlockPhrase: String,
): String {
    val lines = mutableListOf<String>()
    var inCode = false
    for (rawLine in markdown.lineSequence()) {
        if (FENCE.containsMatchIn(rawLine)) {
            if (!inCode) lines += codeBlockPhrase
            inCode = !inCode
            continue
        }
        if (inCode || RULE.matches(rawLine) || TABLE_SEPARATOR.matches(rawLine)) continue
        var line =
            rawLine
                .replace(HEADING, "")
                .replace(BLOCKQUOTE, "")
                .replace(TASK_MARKER, "")
                .replace(LIST_MARKER, "")
        if (line.contains('|')) {
            line =
                line
                    .trim()
                    .trim('|')
                    .split('|')
                    .joinToString(", ") { it.trim() }
        }
        line =
            line
                .replace(IMAGE, "$1")
                .replace(LINK, "$1")
                .replace(INLINE_CODE, "$1")
                .replace(BOLD, "$2")
                .replace(ITALIC_STAR, "$1")
                .replace(ITALIC_UNDERSCORE, "$1")
                .replace(STRIKE, "$1")
                .replace(HTML_TAG, "")
                .replace(BARE_URL, "")
                .replace(SPACES, " ")
                .trim()
        if (line.isNotEmpty()) lines += line
    }
    return lines.joinToString("\n")
}

/** Splits [text] at sentence or line ends so each piece fits one TTS call. */
fun chunkForSpeech(
    text: String,
    maxChars: Int = SPEECH_CHUNK_MAX_CHARS,
): List<String> {
    require(maxChars > 0)
    val chunks = mutableListOf<String>()
    var rest = text.trim()
    while (rest.length > maxChars) {
        val window = rest.substring(0, maxChars)
        val sentenceCut = window.lastIndexOfAny(SENTENCE_END)
        val cut =
            when {
                sentenceCut >= maxChars / 2 -> sentenceCut + 1
                window.lastIndexOf(' ') >= maxChars / 2 -> window.lastIndexOf(' ') + 1
                else -> maxChars
            }
        rest
            .substring(0, cut)
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let(chunks::add)
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) chunks += rest
    return chunks
}

/** en → en-US, zh → zh-CN; other locales pass through. */
fun normalizeSpeechLocale(locale: Locale): Locale =
    when {
        locale.country.isNotEmpty() -> locale
        locale.language == "en" -> Locale.US
        locale.language == "zh" -> Locale.SIMPLIFIED_CHINESE
        else -> locale
    }

/** Recognizer language: the app language. */
fun recognizerLanguageTag(appLocale: Locale): String = normalizeSpeechLocale(appLocale).toLanguageTag()

/**
 * Read-aloud language: the app language, except that a mostly Chinese reply in
 * a non-Chinese app uses zh-CN and a Latin-only reply in a Chinese app uses en-US.
 */
fun chooseSpeechLocale(
    text: String,
    appLocale: Locale,
): Locale {
    var han = 0
    var latin = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN -> han += 1
            Character.UnicodeScript.LATIN -> if (Character.isLetter(codePoint)) latin += 1
            else -> Unit
        }
        index += Character.charCount(codePoint)
    }
    val app = normalizeSpeechLocale(appLocale)
    val appIsChinese = app.language == "zh"
    return when {
        han == 0 && latin == 0 -> app
        han * 3 >= latin -> if (appIsChinese) app else Locale.SIMPLIFIED_CHINESE
        appIsChinese && han == 0 -> Locale.US
        else -> app
    }
}

/** Replies are read aloud automatically only for turns the user started by voice. The mic never reopens. */
fun shouldReadReplyAloud(
    voiceRepliesEnabled: Boolean,
    turnStartedByVoice: Boolean,
    replyText: String,
): Boolean = voiceRepliesEnabled && turnStartedByVoice && replyText.isNotBlank()
