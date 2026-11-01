package ai.androidclaw.feature.chat

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRichTextTest {
    @Test
    fun `parser splits headings bullets paragraphs and code fences`() {
        val blocks =
            parseChatRichText(
                """
                # Title
                
                Intro paragraph here.
                - first item
                - second item
                
                ```kotlin
                println("hello")
                ```
                """.trimIndent(),
            )

        assertEquals(4, blocks.size)
        assertEquals(ChatRichTextBlock.Heading(level = 1, text = "Title"), blocks[0])
        assertEquals(ChatRichTextBlock.Paragraph("Intro paragraph here."), blocks[1])
        assertEquals(
            ChatRichTextBlock.BulletList(listOf("first item", "second item")),
            blocks[2],
        )
        assertEquals(ChatRichTextBlock.CodeFence("println(\"hello\")", language = "kotlin"), blocks[3])
    }

    @Test
    fun `annotated string marks urls and keeps inline code separate`() {
        val annotated =
            buildRichAnnotatedString(
                source = "Use `println()` and visit https://example.com/docs",
                baseStyle = SpanStyle(),
                inlineCodeStyle = SpanStyle(fontFamily = FontFamily.Monospace),
                linkStyle = SpanStyle(fontWeight = FontWeight.Bold),
            )

        val link = annotated.getLinkAnnotations(start = 0, end = annotated.length)
        assertEquals(1, link.size)
        assertEquals("https://example.com/docs", (link.single().item as LinkAnnotation.Url).url)
        assertTrue(annotated.text.contains("println()"))
    }

    @Test
    fun `parser handles ordered lists quotes rules and multi line paragraphs`() {
        val blocks =
            parseChatRichText(
                """
                3. third
                4. fourth

                > quoted
                > line

                ---
                first line
                second line
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                ChatRichTextBlock.OrderedList(start = 3, items = listOf("third", "fourth")),
                ChatRichTextBlock.Quote("quoted\nline"),
                ChatRichTextBlock.Rule,
                ChatRichTextBlock.Paragraph("first line\nsecond line"),
            ),
            blocks,
        )
    }

    @Test
    fun `unterminated code fence keeps the remaining text as code`() {
        val blocks = parseChatRichText("```\nval x = 1")

        assertEquals(listOf(ChatRichTextBlock.CodeFence("val x = 1")), blocks)
    }

    @Test
    fun `inline markdown strips markers and keeps snake case words intact`() {
        val annotated =
            buildRichAnnotatedString(
                source = "**bold** and *italic* and ~~gone~~ in snake_case_name with [docs](https://example.com/a)",
                baseStyle = SpanStyle(),
                inlineCodeStyle = SpanStyle(fontFamily = FontFamily.Monospace),
                linkStyle = SpanStyle(fontWeight = FontWeight.Bold),
            )

        assertEquals("bold and italic and gone in snake_case_name with docs", annotated.text)
        val links = annotated.getLinkAnnotations(start = 0, end = annotated.length)
        assertEquals("https://example.com/a", (links.single().item as LinkAnnotation.Url).url)
    }

    @Test
    fun `long text without markers is rendered in linear time`() {
        val source = "word ".repeat(40_000) + "*unclosed"
        val started = System.nanoTime()

        val annotated =
            buildRichAnnotatedString(
                source = source,
                baseStyle = SpanStyle(),
                inlineCodeStyle = SpanStyle(),
                linkStyle = SpanStyle(),
            )

        assertEquals(source, annotated.text)
        assertTrue((System.nanoTime() - started) < 5_000_000_000L)
    }
}
