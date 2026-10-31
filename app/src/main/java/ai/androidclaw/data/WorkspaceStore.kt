package ai.androidclaw.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

data class WorkspaceFileInfo(
    val path: String,
    val sizeBytes: Long,
    val modifiedAt: Instant,
    val isPersonaFile: Boolean,
)

class WorkspaceException(
    message: String,
) : IllegalArgumentException(message)

/**
 * App-private agent workspace (OpenClaw-style): persona files such as `AGENTS.md`, `SOUL.md`,
 * `USER.md` and `MEMORY.md`, plus any notes the assistant writes with the workspace tools.
 *
 * Every path is relative to [rootDir]; traversal outside it is rejected. File sizes and the
 * file count are bounded so a runaway model cannot fill the device.
 */
class WorkspaceStore(
    val rootDir: File,
) {
    private val mutex = Mutex()

    suspend fun ensureDefaults() =
        io {
            rootDir.mkdirs()
            DEFAULT_TEMPLATES.forEach { (name, template) ->
                val file = File(rootDir, name)
                if (!file.exists()) {
                    file.writeText(template)
                }
            }
        }

    /** Persona files in prompt order, creating defaults first if they are missing. */
    suspend fun personaFiles(): List<WorkspaceFileInfo> {
        ensureDefaults()
        return io {
            PERSONA_FILE_NAMES.mapNotNull { name ->
                File(rootDir, name).takeIf(File::isFile)?.toInfo(name)
            }
        }
    }

    suspend fun list(): List<WorkspaceFileInfo> {
        ensureDefaults()
        return io {
            val rootPath = rootDir.canonicalFile.toPath()
            rootDir
                .walkTopDown()
                .maxDepth(MAX_PATH_DEPTH)
                .filter(File::isFile)
                .take(MAX_FILE_COUNT)
                .map { file ->
                    val relative = rootPath.relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')
                    file.toInfo(relative)
                }.sortedWith(compareBy<WorkspaceFileInfo>({ !it.isPersonaFile }, { it.path }))
                .toList()
        }
    }

    /** Returns the file text, or null when the file does not exist. */
    suspend fun read(path: String): String? {
        ensureDefaults()
        val file = resolve(path)
        return io { file.takeIf(File::isFile)?.readText() }
    }

    suspend fun write(
        path: String,
        content: String,
        append: Boolean = false,
    ): WorkspaceFileInfo {
        val file = resolve(path)
        return io {
            val existing = if (append && file.isFile) file.readText() else ""
            val next = existing + content
            requireWithinFileLimit(path, next)
            if (!file.exists()) {
                requireFileCapacity()
            }
            file.parentFile?.mkdirs()
            file.writeText(next)
            file.toInfo(normalize(path))
        }
    }

    suspend fun delete(path: String): Boolean {
        val file = resolve(path)
        return io { file.isFile && file.delete() }
    }

    /** Restores a persona file to its default template. Returns false for non-persona paths. */
    suspend fun reset(path: String): Boolean {
        val name = normalize(path)
        val template = DEFAULT_TEMPLATES[name] ?: return false
        write(name, template)
        return true
    }

    fun defaultTemplate(path: String): String? = DEFAULT_TEMPLATES[runCatching { normalize(path) }.getOrNull()]

    /**
     * Renders the persona files for the system prompt within [maxBytes] (UTF-8). Truncation is
     * marked so the model knows to read the full file with `workspace_read`.
     */
    suspend fun promptContext(maxBytes: Int = PROMPT_CONTEXT_MAX_BYTES): String? {
        ensureDefaults()
        val sections =
            io {
                PERSONA_FILE_NAMES.mapNotNull { name ->
                    val text = File(rootDir, name).takeIf(File::isFile)?.readText()?.trim()
                    text?.takeIf(String::isNotBlank)?.let { name to it }
                }
            }
        if (sections.isEmpty()) {
            return null
        }
        var remaining = maxBytes
        val rendered =
            buildString {
                for ((name, text) in sections) {
                    val header = "## $name\n"
                    val headerBytes = header.utf8Size()
                    if (remaining <= headerBytes + MIN_SECTION_BYTES) {
                        val note = "[workspace context budget reached; read $name with workspace_read]\n"
                        if (note.utf8Size() <= remaining) append(note)
                        break
                    }
                    append(header)
                    remaining -= headerBytes
                    val textBytes = text.utf8Size()
                    if (textBytes + 1 <= remaining) {
                        append(text).append('\n')
                        remaining -= textBytes + 1
                    } else {
                        val marker = "\n[truncated: $name is longer; read it with workspace_read]\n"
                        val kept = text.takeUtf8Bytes((remaining - marker.utf8Size()).coerceAtLeast(0))
                        append(kept).append(marker)
                        break
                    }
                    append('\n')
                    remaining -= 1
                }
            }
        return rendered.trim()
    }

    internal fun resolve(path: String): File {
        val normalized = normalize(path)
        val candidate = File(rootDir, normalized)
        val rootCanonical = rootDir.canonicalFile
        val candidateCanonical = candidate.canonicalFile
        if (!candidateCanonical.path.startsWith(rootCanonical.path + File.separator)) {
            throw WorkspaceException("Path $path is outside the workspace.")
        }
        return candidateCanonical
    }

    private fun normalize(path: String): String {
        val trimmed =
            path
                .trim()
                .replace('\\', '/')
                .trimStart('/')
                .removePrefix("./")
        if (trimmed.isBlank()) {
            throw WorkspaceException("Workspace path must not be blank.")
        }
        if (trimmed.length > MAX_PATH_CHARS) {
            throw WorkspaceException("Workspace path is longer than $MAX_PATH_CHARS characters.")
        }
        val segments = trimmed.split('/').filter(String::isNotEmpty)
        if (segments.size > MAX_PATH_DEPTH) {
            throw WorkspaceException("Workspace path is nested deeper than $MAX_PATH_DEPTH folders.")
        }
        segments.forEach { segment ->
            if (segment == "." || segment == "..") {
                throw WorkspaceException("Workspace path must not contain '.' or '..' segments.")
            }
            if (segment.any { char -> char.isISOControl() || char in FORBIDDEN_PATH_CHARS }) {
                throw WorkspaceException("Workspace path contains unsupported characters.")
            }
        }
        return segments.joinToString("/")
    }

    private fun requireWithinFileLimit(
        path: String,
        content: String,
    ) {
        if (content.utf8Size() > MAX_FILE_BYTES) {
            throw WorkspaceException("Workspace file $path would exceed ${MAX_FILE_BYTES / 1024} KB.")
        }
    }

    private fun requireFileCapacity() {
        val count =
            rootDir
                .walkTopDown()
                .filter(File::isFile)
                .take(MAX_FILE_COUNT + 1)
                .count()
        if (count >= MAX_FILE_COUNT) {
            throw WorkspaceException("Workspace already holds $MAX_FILE_COUNT files; delete some first.")
        }
    }

    private fun File.toInfo(relativePath: String): WorkspaceFileInfo =
        WorkspaceFileInfo(
            path = relativePath,
            sizeBytes = length(),
            modifiedAt = Instant.ofEpochMilli(lastModified()),
            isPersonaFile = relativePath in PERSONA_FILE_NAMES,
        )

    private suspend fun <T> io(block: () -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock { block() }
        }

    companion object {
        const val DIRECTORY_NAME = "agent_workspace"
        const val PROMPT_CONTEXT_MAX_BYTES = 8 * 1024
        const val MAX_FILE_BYTES = 256 * 1024
        const val MAX_FILE_COUNT = 200
        const val MAX_PATH_CHARS = 200
        const val MAX_PATH_DEPTH = 6
        private const val MIN_SECTION_BYTES = 128
        private val FORBIDDEN_PATH_CHARS = setOf(':', '*', '?', '"', '<', '>', '|')

        val PERSONA_FILE_NAMES: List<String> = listOf("AGENTS.md", "SOUL.md", "USER.md", "MEMORY.md")

        val DEFAULT_TEMPLATES: Map<String, String> =
            linkedMapOf(
                "AGENTS.md" to
                    """
                    # AGENTS.md - how to work
                    - You run on the user's Android phone inside AndroidClaw.
                    - Prefer a tool over guessing. Keep replies short unless asked for depth.
                    - Reply in the language the user writes in.
                    - Save durable facts about the user in USER.md and long-term notes in MEMORY.md
                      (workspace_edit or workspace_write). Keep both files short.
                    - Opening apps, links, alarms, timers and calendar entries shows system UI;
                      do it when the user asks for it.
                    """.trimIndent() + "\n",
                "SOUL.md" to
                    """
                    # SOUL.md - persona
                    You are AndroidClaw, a calm and practical pocket assistant.
                    Be direct, friendly and honest about what you cannot do on this phone.
                    """.trimIndent() + "\n",
                "USER.md" to
                    """
                    # USER.md - about the user
                    Name, preferred language, timezone and preferences go here.
                    """.trimIndent() + "\n",
                "MEMORY.md" to
                    """
                    # MEMORY.md - long-term notes
                    Durable facts and decisions worth keeping across sessions go here.
                    """.trimIndent() + "\n",
            )
    }
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private fun String.takeUtf8Bytes(maxBytes: Int): String {
    var used = 0
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val bytes =
            when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
        if (used + bytes > maxBytes) {
            break
        }
        used += bytes
        index += Character.charCount(codePoint)
    }
    return substring(0, index)
}
