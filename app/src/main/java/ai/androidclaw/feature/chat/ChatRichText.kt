package ai.androidclaw.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

internal sealed interface ChatRichTextBlock {
    data class Heading(
        val level: Int,
        val text: String,
    ) : ChatRichTextBlock

    data class Paragraph(val text: String) : ChatRichTextBlock

    data class BulletList(val items: List<String>) : ChatRichTextBlock

    data class CodeFence(val code: String) : ChatRichTextBlock
}

@Composable
fun ChatRichText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val blocks = parseChatRichText(text)
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is ChatRichTextBlock.Heading -> {
                    Text(
                        text = block.text,
                        style = if (block.level <= 1) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleSmall
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                is ChatRichTextBlock.Paragraph -> {
                    RichAnnotatedText(
                        text = block.text,
                        style = style,
                        onOpenUri = uriHandler::openUri,
                    )
                }

                is ChatRichTextBlock.BulletList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEach { item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "\u2022",
                                    style = style,
                                )
                                RichAnnotatedText(
                                    text = item,
                                    style = style,
                                    modifier = Modifier.weight(1f),
                                    onOpenUri = uriHandler::openUri,
                                )
                            }
                        }
                    }
                }

                is ChatRichTextBlock.CodeFence -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            text = block.code,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
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
    val annotated = buildRichAnnotatedString(
        source = text,
        baseStyle = style.toSpanStyle(),
        inlineCodeStyle = SpanStyle(
            fontFamily = FontFamily.Monospace,
            background = MaterialTheme.colorScheme.surfaceVariant,
        ),
        linkStyle = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        ),
        onOpenUri = onOpenUri,
    )
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
    )
}

internal fun parseChatRichText(source: String): List<ChatRichTextBlock> {
    val normalized = source.trimEnd()
    if (normalized.isBlank()) {
        return listOf(ChatRichTextBlock.Paragraph(""))
    }

    val lines = normalized.lines()
    val blocks = mutableListOf<ChatRichTextBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.isBlank()) {
            index += 1
            continue
        }
        if (trimmed.startsWith("```")) {
            index += 1
            val codeLines = mutableListOf<String>()
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                codeLines += lines[index]
                index += 1
            }
            if (index < lines.size && lines[index].trim().startsWith("```")) {
                index += 1
            }
            blocks += ChatRichTextBlock.CodeFence(codeLines.joinToString("\n").trimEnd())
            continue
        }
        if (trimmed.startsWith("#")) {
            val level = trimmed.takeWhile { it == '#' }.length.coerceAtLeast(1)
            val content = trimmed.drop(level).trim()
            blocks += ChatRichTextBlock.Heading(level = level, text = content.ifBlank { trimmed })
            index += 1
            continue
        }
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            val items = mutableListOf<String>()
            while (index < lines.size) {
                val bulletLine = lines[index].trim()
                if (!bulletLine.startsWith("- ") && !bulletLine.startsWith("* ")) {
                    break
                }
                items += bulletLine.drop(2).trim()
                index += 1
            }
            blocks += ChatRichTextBlock.BulletList(items)
            continue
        }

        val paragraphLines = mutableListOf(trimmed)
        index += 1
        while (index < lines.size) {
            val next = lines[index].trim()
            if (
                next.isBlank() ||
                next.startsWith("```") ||
                next.startsWith("#") ||
                next.startsWith("- ") ||
                next.startsWith("* ")
            ) {
                break
            }
            paragraphLines += next
            index += 1
        }
        blocks += ChatRichTextBlock.Paragraph(paragraphLines.joinToString(" "))
    }
    return blocks.ifEmpty { listOf(ChatRichTextBlock.Paragraph(normalized)) }
}

internal fun buildRichAnnotatedString(
    source: String,
    baseStyle: SpanStyle,
    inlineCodeStyle: SpanStyle,
    linkStyle: SpanStyle,
    onOpenUri: (String) -> Unit = {},
): AnnotatedString {
    return buildAnnotatedString {
        var index = 0
        var inlineCode = false
        val urlRegex = URL_REGEX
        while (index < source.length) {
            val char = source[index]
            if (char == '`') {
                inlineCode = !inlineCode
                index += 1
                continue
            }
            if (!inlineCode) {
                val urlMatch = urlRegex.find(source, index)
                if (urlMatch != null && urlMatch.range.first == index) {
                    val url = urlMatch.value
                    withLink(
                        LinkAnnotation.Url(
                            url = url,
                            styles = TextLinkStyles(style = linkStyle),
                            linkInteractionListener = LinkInteractionListener { annotation ->
                                onOpenUri((annotation as? LinkAnnotation.Url)?.url ?: url)
                            },
                        ),
                    ) {
                        append(urlMatch.value)
                    }
                    index = urlMatch.range.last + 1
                    continue
                }
            }

            if (inlineCode) {
                withStyle(inlineCodeStyle) {
                    append(char)
                }
            } else {
                withStyle(baseStyle) {
                    append(char)
                }
            }
            index += 1
        }
    }
}

private val URL_REGEX = Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
