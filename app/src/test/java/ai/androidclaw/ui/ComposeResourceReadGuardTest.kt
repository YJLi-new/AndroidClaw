package ai.androidclaw.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guard for Compose lint `LocalContextGetResourceValueCall` / `LocalContextResourcesRead`:
 * inside a `@Composable` function, resource values must come from `stringResource(...)` or
 * `LocalResources.current`, never from `LocalContext.current` (those reads are not invalidated on a
 * configuration change such as a language switch).
 *
 * CI's lint reports this, but on the WSL workstation the Compose lint detector does not fire (see
 * handoff/int.md), so this test keeps the local fast loop honest. Plain helper functions that take a
 * `Context` parameter are not composable and are not checked.
 */
class ComposeResourceReadGuardTest {
    @Test
    fun `composables do not read resource values through LocalContext`() {
        val sourceRoot = File("src/main/java")
        assertTrue("Run from the app module: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        val findings =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> findLocalContextResourceReads(file.readLines()).map { "${file.path}:$it" } }
                .toList()
        assertTrue("LocalContext resource reads in composables:\n" + findings.joinToString("\n"), findings.isEmpty())
    }

    @Test
    fun `guard flags direct and aliased reads only inside composables`() {
        val source =
            """
            @Composable
            fun Screen() {
                val context = LocalContext.current
                val title = LocalContext.current.getString(R.string.a)
                val onCopied = { show(context.getString(R.string.b)) }
                val size = context.resources.displayMetrics
                context.startActivity(intent)
            }

            fun share(context: Context) {
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.c)))
            }
            """.trimIndent().lines()

        assertTrue(findLocalContextResourceReads(source) == listOf(4, 5, 6))
    }
}

private val TOP_LEVEL_FUN = Regex("""^(?:(?:private|internal|public|inline)\s+)*fun\s""")
private val CONTEXT_ALIAS = Regex("""\bva[lr]\s+(\w+)\s*=\s*LocalContext\.current\b""")
private const val RESOURCE_CALLS = "getString|getText|getQuantityString|getStringArray|getColor|getDrawable|getDimension|resources"

/** 1-based line numbers of LocalContext resource reads inside `@Composable` top-level functions. */
internal fun findLocalContextResourceReads(lines: List<String>): List<Int> {
    val hits = mutableListOf<Int>()
    var inComposable = false
    var aliases = emptySet<String>()
    lines.forEachIndexed { index, line ->
        if (TOP_LEVEL_FUN.containsMatchIn(line)) {
            inComposable = lines.subList(maxOf(0, index - 3), index).any { it.trim().startsWith("@Composable") }
            aliases = emptySet()
        }
        if (!inComposable) return@forEachIndexed
        CONTEXT_ALIAS.find(line)?.let { aliases = aliases + it.groupValues[1] }
        val receivers = (aliases.map(Regex::escape) + "LocalContext\\.current").joinToString("|")
        val code = line.substringBefore("//")
        if (Regex("""(?:^|[^\w.])(?:$receivers)\.(?:$RESOURCE_CALLS)\b""").containsMatchIn(code)) {
            hits += index + 1
        }
    }
    return hits
}
