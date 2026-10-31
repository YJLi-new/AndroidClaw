package ai.androidclaw.feature.chat

import ai.androidclaw.R
import ai.androidclaw.ui.components.ClawIconButton
import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal sealed interface ChatRichTextBlock {
    data class Heading(
        val level: Int,
        val text: String,
    ) : ChatRichTextBlock

    data class Paragraph(
        val text: String,
    ) : ChatRichTextBlock

    data class BulletList(
        val items: List<String>,
    ) : ChatRichTextBlock

    data class OrderedList(
        val start: Int,
        val items: List<String>,
    ) : ChatRichTextBlock

    data class Quote(
        val text: String,
    ) : ChatRichTextBlock

    data class CodeFence(
        val code: String,
        val language: String = "",
    ) : ChatRichTextBlock

    data object Rule : ChatRichTextBlock
}

/** Lightweight Markdown renderer for chat messages (no external parser). */
@Composable
fun ChatRichText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val blocks = remember(text) { parseChatRichText(text) }
    val uriHandler = LocalUriHandler.current
    val openUri: (String) -> Unit = { uri -> runCatching { uriHandler.openUri(uri) } }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is ChatRichTextBlock.Heading ->
                    RichAnnotatedText(
                        text = block.text,
                        style =
                            when (block.level) {
                                1 -> MaterialTheme.typography.titleLarge
                                2 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            }.copy(fontWeight = FontWeight.SemiBold, color = style.color),
                        onOpenUri = openUri,
                    )

                is ChatRichTextBlock.Paragraph ->
                    RichAnnotatedText(text = block.text, style = style, onOpenUri = openUri)

                is ChatRichTextBlock.BulletList ->
                    ListBlock(
                        markers = block.items.map { "•" },
                        items = block.items,
                        style = style,
                        onOpenUri = openUri,
                    )

                is ChatRichTextBlock.OrderedList ->
                    ListBlock(
                        markers = block.items.indices.map { "${block.start + it}." },
                        items = block.items,
                        style = style,
                        onOpenUri = openUri,
                    )

                is ChatRichTextBlock.Quote ->
                    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                        Box(
                            modifier =
                                Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        RichAnnotatedText(
                            text = block.text,
                            style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(start = 10.dp),
                            onOpenUri = openUri,
                        )
                    }

                is ChatRichTextBlock.CodeFence -> CodeBlock(block)

                ChatRichTextBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ListBlock(
    markers: List<String>,
    items: List<String>,
    style: TextStyle,
    onOpenUri: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = markers[index], style = style)
                RichAnnotatedText(
                    text = item,
                    style = style,
                    modifier = Modifier.weight(1f),
                    onOpenUri = onOpenUri,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(block: ChatRichTextBlock.CodeFence) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language.ifBlank { stringResource(R.string.chat_code_block_label) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ClawIconButton(
                    iconRes = R.drawable.ic_copy,
                    contentDescription = stringResource(R.string.chat_action_copy_code),
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", block.code)))
                        }
                    },
                )
            }
            Text(
                text = block.code,
                modifier =
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun RichAnnotatedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onOpenUri: (String) -> Unit,
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated =
        remember(text, codeBackground, linkColor) {
            buildRichAnnotatedString(
                source = text,
                baseStyle = SpanStyle(),
                inlineCodeStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
                linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                onOpenUri = onOpenUri,
            )
        }
    Text(text = annotated, modifier = modifier, style = style)
}

private val ORDERED_ITEM = Regex("^(\\d{1,4})[.)]\\s+(.*)$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")

private fun String.bulletContent(): String? =
    if (length >= 2 && (this[0] == '-' || this[0] == '*' || this[0] == '+') && this[1] == ' ') {
        substring(2).trim()
    } else {
        null
    }

private fun String.isRule(): Boolean {
    val compact = replace(" ", "")
    return compact.length >= 3 && (compact.all { it == '-' } || compact.all { it == '*' } || compact.all { it == '_' })
}

private fun String.startsBlock(): Boolean =
    startsWith("```") ||
        HEADING.matches(this) ||
        bulletContent() != null ||
        ORDERED_ITEM.matches(this) ||
        startsWith(">") ||
        isRule()

internal fun parseChatRichText(source: String): List<ChatRichTextBlock> {
    val normalized = source.trimEnd()
    if (normalized.isBlank()) {
        return listOf(ChatRichTextBlock.Paragraph(""))
    }

    val lines = normalized.lines()
    val blocks = mutableListOf<ChatRichTextBlock>()
    var index = 0
    while (index < lines.size) {
        val trimmed = lines[index].trim()
        when {
            trimmed.isBlank() -> index += 1

            trimmed.startsWith("```") -> {
                val language = trimmed.removePrefix("```").trim()
                index += 1
                val codeLines = mutableListOf<String>()
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    codeLines += lines[index]
                    index += 1
                }
                if (index < lines.size) {
                    index += 1
                }
                blocks += ChatRichTextBlock.CodeFence(codeLines.joinToString("\n").trimEnd(), language)
            }

            trimmed.isRule() -> {
                blocks += ChatRichTextBlock.Rule
                index += 1
            }

            HEADING.matches(trimmed) -> {
                val match = HEADING.find(trimmed)!!
                blocks += ChatRichTextBlock.Heading(level = match.groupValues[1].length, text = match.groupValues[2].trim())
                index += 1
            }

            trimmed.bulletContent() != null -> {
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val item = lines[index].trim().bulletContent() ?: break
                    items += item
                    index += 1
                }
                blocks += ChatRichTextBlock.BulletList(items)
            }

            ORDERED_ITEM.matches(trimmed) -> {
                val start = ORDERED_ITEM.find(trimmed)!!.groupValues[1].toInt()
                val items = mutableListOf<String>()
                while (index < lines.size) {
                    val match = ORDERED_ITEM.find(lines[index].trim()) ?: break
                    items += match.groupValues[2].trim()
                    index += 1
                }
                blocks += ChatRichTextBlock.OrderedList(start, items)
            }

            trimmed.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quoteLines += lines[index].trim().removePrefix(">").trim()
                    index += 1
                }
                blocks += ChatRichTextBlock.Quote(quoteLines.joinToString("\n"))
            }

            else -> {
                val paragraphLines = mutableListOf(trimmed)
                index += 1
                while (index < lines.size) {
                    val next = lines[index].trim()
                    if (next.isBlank() || next.startsBlock()) {
                        break
                    }
                    paragraphLines += next
                    index += 1
                }
                // Chat replies use single newlines as line breaks, so keep them.
                blocks += ChatRichTextBlock.Paragraph(paragraphLines.joinToString("\n"))
            }
        }
    }
    return blocks.ifEmpty { listOf(ChatRichTextBlock.Paragraph(normalized)) }
}

private const val INLINE_MARKER_SEARCH_LIMIT = 2_000

/**
 * Single pass inline Markdown: `code`, **bold**, *italic* / _italic_,
 * ~~strike~~, [label](url) and bare http(s) URLs.
 */
internal fun buildRichAnnotatedString(
    source: String,
    baseStyle: SpanStyle,
    inlineCodeStyle: SpanStyle,
    linkStyle: SpanStyle,
    onOpenUri: (String) -> Unit = {},
): AnnotatedString =
    buildAnnotatedString {
        withStyle(baseStyle) {
            appendInline(source, inlineCodeStyle, linkStyle, onOpenUri)
        }
    }

private fun AnnotatedString.Builder.appendInline(
    source: String,
    inlineCodeStyle: SpanStyle,
    linkStyle: SpanStyle,
    onOpenUri: (String) -> Unit,
) {
    var index = 0
    val plain = StringBuilder()

    fun flush() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.clear()
        }
    }
    while (index < source.length) {
        val char = source[index]
        val closing =
            when {
                char == '`' -> source.closingIndex("`", index + 1)
                source.startsWith("**", index) || source.startsWith("__", index) ->
                    source.closingIndex(source.substring(index, index + 2), index + 2)
                source.startsWith("~~", index) -> source.closingIndex("~~", index + 2)
                (char == '*' || char == '_') && source.canOpenEmphasis(index) -> source.closingEmphasis(char, index + 1)
                else -> -1
            }
        when {
            char == '`' && closing > index + 1 -> {
                flush()
                withStyle(inlineCodeStyle) { append(source.substring(index + 1, closing)) }
                index = closing + 1
            }

            (source.startsWith("**", index) || source.startsWith("__", index)) && closing > index + 2 -> {
                flush()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInline(source.substring(index + 2, closing), inlineCodeStyle, linkStyle, onOpenUri)
                }
                index = closing + 2
            }

            source.startsWith("~~", index) && closing > index + 2 -> {
                flush()
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInline(source.substring(index + 2, closing), inlineCodeStyle, linkStyle, onOpenUri)
                }
                index = closing + 2
            }

            (char == '*' || char == '_') && closing > index + 1 -> {
                flush()
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInline(source.substring(index + 1, closing), inlineCodeStyle, linkStyle, onOpenUri)
                }
                index = closing + 1
            }

            char == '[' -> {
                val link = source.markdownLinkAt(index)
                if (link != null) {
                    flush()
                    appendLink(link.label, link.url, linkStyle, onOpenUri)
                    index = link.end
                } else {
                    plain.append(char)
                    index += 1
                }
            }

            char == 'h' && (source.startsWith("https://", index) || source.startsWith("http://", index)) -> {
                val end = source.urlEnd(index)
                flush()
                val url = source.substring(index, end)
                appendLink(url, url, linkStyle, onOpenUri)
                index = end
            }

            else -> {
                plain.append(char)
                index += 1
            }
        }
    }
    flush()
}

private fun AnnotatedString.Builder.appendLink(
    label: String,
    url: String,
    linkStyle: SpanStyle,
    onOpenUri: (String) -> Unit,
) {
    withLink(
        LinkAnnotation.Url(
            url = url,
            styles = TextLinkStyles(style = linkStyle),
            linkInteractionListener =
                LinkInteractionListener { annotation ->
                    onOpenUri((annotation as? LinkAnnotation.Url)?.url ?: url)
                },
        ),
    ) {
        append(label)
    }
}

private fun String.closingIndex(
    marker: String,
    from: Int,
): Int {
    if (from >= length) return -1
    val found = indexOf(marker, from)
    return if (found < 0 || found - from > INLINE_MARKER_SEARCH_LIMIT || found == from) -1 else found
}

private fun String.canOpenEmphasis(index: Int): Boolean {
    val next = getOrNull(index + 1) ?: return false
    if (next.isWhitespace() || next == this[index]) return false
    // Avoid snake_case and 2*3*4 style text.
    val previous = getOrNull(index - 1)
    return previous == null || !previous.isLetterOrDigit()
}

private fun String.closingEmphasis(
    marker: Char,
    from: Int,
): Int {
    val limit = minOf(length, from + INLINE_MARKER_SEARCH_LIMIT)
    var cursor = from
    while (cursor < limit) {
        if (this[cursor] == '\n') return -1
        if (this[cursor] == marker && !this[cursor - 1].isWhitespace()) {
            val after = getOrNull(cursor + 1)
            if (after == null || !after.isLetterOrDigit()) return cursor
        }
        cursor += 1
    }
    return -1
}

private data class MarkdownLink(
    val label: String,
    val url: String,
    val end: Int,
)

private fun String.markdownLinkAt(index: Int): MarkdownLink? {
    val labelEnd = indexOf("](", index + 1)
    if (labelEnd < 0 || labelEnd - index > INLINE_MARKER_SEARCH_LIMIT) return null
    val urlEnd = indexOf(')', labelEnd + 2)
    if (urlEnd < 0) return null
    val label = substring(index + 1, labelEnd)
    val url = substring(labelEnd + 2, urlEnd).trim()
    if (label.isBlank() || '\n' in label) return null
    if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("mailto:")) return null
    return MarkdownLink(label = label, url = url, end = urlEnd + 1)
}

private fun String.urlEnd(start: Int): Int {
    var end = start
    while (end < length && isUrlChar(this[end])) {
        end += 1
    }
    while (end > start && this[end - 1] in ".,;:!?)'\"") {
        end -= 1
    }
    return end
}

private fun isUrlChar(char: Char): Boolean = char.isLetterOrDigit() || char in "-._~:/?#[]@!$&'()*+,;=%"
