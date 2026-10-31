package ai.androidclaw.ui.locale

import ai.androidclaw.R
import ai.androidclaw.testutil.text
import ai.androidclaw.ui.text.UiText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    @Test
    fun `simplified chinese defines every translatable english key`() {
        val english = readResources("values")
        val chinese = readResources("values-zh-rCN")

        val missing = english.keys - chinese.keys
        val extra = chinese.keys - english.keys
        assertTrue("Missing zh-rCN keys: $missing", missing.isEmpty())
        assertTrue("zh-rCN keys absent from values: $extra", extra.isEmpty())
    }

    @Test
    fun `translations keep the same format placeholders`() {
        val english = readResources("values")
        val chinese = readResources("values-zh-rCN")

        val mismatched =
            english.filter { (key, placeholders) -> chinese[key] != null && chinese[key] != placeholders }.keys
        assertTrue("Placeholder mismatch: $mismatched", mismatched.isEmpty())
    }

    @Test
    fun `language tags map to app languages`() {
        assertEquals(AppLanguage.System, AppLanguage.fromLanguageTag(null))
        assertEquals(AppLanguage.System, AppLanguage.fromLanguageTag(" "))
        assertEquals(AppLanguage.English, AppLanguage.fromLanguageTag("en-US"))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromLanguageTag("zh-CN"))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromLanguageTag("zh-Hans-CN"))
        assertEquals(AppLanguage.System, AppLanguage.fromLanguageTag("fr"))
    }

    @Test
    fun `ui text resolves english resources by default`() {
        assertEquals("Queued run now for Daily.", UiText.res(R.string.tasks_notice_run_now, "Daily").text())
        assertEquals("Every 2 hours", UiText.plural(R.plurals.tasks_every_hours, 2).text())
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `ui text resolves simplified chinese resources`() {
        assertEquals("已安排立即运行 Daily。", UiText.res(R.string.tasks_notice_run_now, "Daily").text())
        assertEquals("每 2 小时", UiText.plural(R.plurals.tasks_every_hours, 2).text())
        assertEquals("聊天", UiText.res(R.string.nav_chat).text())
    }

    /** Maps each translatable string / plurals key to its placeholder set. */
    private fun readResources(folder: String): Map<String, Set<String>> {
        val file = listOf("src/main/res/$folder/strings.xml", "app/src/main/res/$folder/strings.xml").map(::File).first { it.exists() }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = LinkedHashMap<String, Set<String>>()
        for (tag in listOf("string", "plurals")) {
            val nodes = document.getElementsByTagName(tag)
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                if (element.getAttribute("translatable") == "false") continue
                result["$tag/${element.getAttribute("name")}"] = PLACEHOLDER.findAll(element.textContent).map { it.value }.toSet()
            }
        }
        return result
    }

    private companion object {
        val PLACEHOLDER = Regex("%\\d\\$[sd]")
    }
}
