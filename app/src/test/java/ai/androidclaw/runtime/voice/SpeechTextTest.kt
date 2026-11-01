package ai.androidclaw.runtime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SpeechTextTest {
    @Test
    fun `markdown markers are stripped and links keep their label`() {
        val markdown =
            """
            # Weather today
            **Sunny** and *warm*, see [the forecast](https://example.com/f) or https://example.com/raw.
            > Quoted ~~old~~ note
            - first item with `inline_code`
            2. second item
            - [x] done task
            ---
            ![chart](https://example.com/c.png)
            <b>bold</b> snake_case_name stays
            """.trimIndent()

        val speech = speechTextFromMarkdown(markdown, codeBlockPhrase = "Code block omitted.")

        assertEquals(
            listOf(
                "Weather today",
                "Sunny and warm, see the forecast or",
                "Quoted old note",
                "first item with inline_code",
                "second item",
                "done task",
                "chart",
                "bold snake_case_name stays",
            ).joinToString("\n"),
            speech,
        )
    }

    @Test
    fun `each code block becomes the localized phrase once`() {
        val markdown =
            """
            Run this:
            ```kotlin
            val x = 1
            println(x)
            ```
            Then this:
            ~~~
            ls -la
            ~~~
            Done.
            """.trimIndent()

        val speech = speechTextFromMarkdown(markdown, codeBlockPhrase = "此处省略代码块。")

        assertEquals("Run this:\n此处省略代码块。\nThen this:\n此处省略代码块。\nDone.", speech)
        assertFalse(speech.contains("println"))
    }

    @Test
    fun `tables read as comma separated cells without the separator row`() {
        val markdown =
            """
            | City | Temp |
            |------|:----:|
            | Oslo | 4 |
            """.trimIndent()

        assertEquals("City, Temp\nOslo, 4", speechTextFromMarkdown(markdown, codeBlockPhrase = "x"))
    }

    @Test
    fun `short text stays one chunk and long text splits at sentence ends`() {
        assertEquals(listOf("Hello there."), chunkForSpeech("  Hello there.  "))
        assertEquals(emptyList<String>(), chunkForSpeech("   "))

        val sentence = "This sentence has exactly forty chars.. "
        val text = sentence.repeat(10).trim()
        val chunks = chunkForSpeech(text, maxChars = 100)

        assertTrue(chunks.all { it.length <= 100 })
        assertTrue(chunks.all { it.endsWith(".") })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun `text without boundaries is cut hard at the limit`() {
        val chunks = chunkForSpeech("x".repeat(250), maxChars = 100)

        assertEquals(listOf(100, 100, 50), chunks.map(String::length))
    }

    @Test
    fun `recognizer language follows the app language with a region`() {
        assertEquals("en-US", recognizerLanguageTag(Locale.ENGLISH))
        assertEquals("zh-CN", recognizerLanguageTag(Locale.forLanguageTag("zh")))
        assertEquals("en-GB", recognizerLanguageTag(Locale.UK))
        assertEquals("fr-FR", recognizerLanguageTag(Locale.FRANCE))
    }

    @Test
    fun `speech language uses the app language unless the reply is clearly another script`() {
        val english = Locale.forLanguageTag("en")
        val chinese = Locale.forLanguageTag("zh-CN")

        assertEquals(Locale.US, chooseSpeechLocale("The weather is sunny.", english))
        assertEquals(Locale.SIMPLIFIED_CHINESE, chooseSpeechLocale("今天天气晴朗，适合出门。", english))
        assertEquals(chinese, chooseSpeechLocale("今天用 Kotlin 写代码。", chinese))
        assertEquals(Locale.US, chooseSpeechLocale("Only English words here.", chinese))
        assertEquals(chinese, chooseSpeechLocale("12345 !!!", chinese))
        assertEquals(Locale.US, chooseSpeechLocale("安卓 is the Chinese name for the Android platform.", english))
    }

    @Test
    fun `replies are read aloud only for voice turns with voice replies on`() {
        assertTrue(shouldReadReplyAloud(voiceRepliesEnabled = true, turnStartedByVoice = true, replyText = "Hi"))
        assertFalse(shouldReadReplyAloud(voiceRepliesEnabled = false, turnStartedByVoice = true, replyText = "Hi"))
        assertFalse(shouldReadReplyAloud(voiceRepliesEnabled = true, turnStartedByVoice = false, replyText = "Hi"))
        assertFalse(shouldReadReplyAloud(voiceRepliesEnabled = true, turnStartedByVoice = true, replyText = "  "))
    }
}
