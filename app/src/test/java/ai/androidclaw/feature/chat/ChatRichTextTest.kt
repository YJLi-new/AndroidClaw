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
        assertEquals(ChatRichTextBlock.CodeFence("println(\"hello\")"), blocks[3])
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
}
