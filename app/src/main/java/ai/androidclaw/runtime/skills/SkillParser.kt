package ai.androidclaw.runtime.skills

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.yaml.snakeyaml.Yaml

class SkillParser {
    private val yaml = Yaml()
    private val supportedFields =
        setOf(
            "name",
            "description",
            "homepage",
            "user-invocable",
            "disable-model-invocation",
            "command-dispatch",
            "command-tool",
            "command-arg-mode",
            "metadata",
        )

    fun parse(rawMarkdown: String): SkillParseResult {
        val normalized = rawMarkdown.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) {
            return SkillParseResult.Failure("SKILL.md must begin with YAML frontmatter.")
        }

        val inlineClosingMarker = normalized.indexOf("\n---\n", startIndex = 4)
        val eofClosingMarker = if (normalized.endsWith("\n---")) normalized.length - 4 else -1
        val closingMarker =
            when {
                inlineClosingMarker != -1 -> inlineClosingMarker
                eofClosingMarker != -1 -> eofClosingMarker
                else -> -1
            }
        if (closingMarker == -1) {
            return SkillParseResult.Failure("SKILL.md frontmatter is not closed with ---")
        }

        val rawFrontmatter = normalized.substring(4, closingMarker)
        val bodyStart = if (inlineClosingMarker != -1) closingMarker + 5 else normalized.length
        val body = normalized.substring(bodyStart).trim()
        val frontmatterMap =
            runCatching {
                @Suppress("UNCHECKED_CAST")
                yaml.load<Any?>(rawFrontmatter) as? Map<String, Any?>
            }.getOrNull()
                ?: return SkillParseResult.Failure("Frontmatter is not a YAML object.")

        val name = frontmatterMap["name"]?.toString()?.trim().orEmpty()
        val description = frontmatterMap["description"]?.toString()?.trim().orEmpty()
        if (name.isBlank() || description.isBlank()) {
            return SkillParseResult.Failure("Skill frontmatter must contain non-empty name and description.")
        }

        val metadata = parseMetadata(frontmatterMap["metadata"])
        val unknownFields =
            buildMap {
                frontmatterMap.forEach { (key, value) ->
                    if (key !in supportedFields) {
                        put(key, value.toJsonElement())
                    }
                }
            }

        return SkillParseResult.Success(
            document =
                ParsedSkillDocument(
                    frontmatter =
                        SkillFrontmatter(
                            name = name,
                            description = description,
                            homepage = frontmatterMap["homepage"]?.toString(),
                            userInvocable = frontmatterMap["user-invocable"]?.asBoolean() ?: true,
                            disableModelInvocation = frontmatterMap["disable-model-invocation"]?.asBoolean() ?: false,
                            commandDispatch =
                                when (frontmatterMap["command-dispatch"]?.toString()) {
                                    "tool" -> SkillCommandDispatch.Tool
                                    else -> SkillCommandDispatch.Model
                                },
                            commandTool = frontmatterMap["command-tool"]?.toString(),
                            commandArgMode = frontmatterMap["command-arg-mode"]?.toString() ?: "raw",
                            metadata = metadata,
                            unknownFields = unknownFields,
                        ),
                    bodyMarkdown = body,
                    rawFrontmatter = rawFrontmatter,
                ),
        )
    }

    private fun parseMetadata(value: Any?): JsonElement? {
        if (value == null) return null
        if (value is String) {
            val trimmed = value.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val parsed = runCatching { yaml.load<Any?>(trimmed) }.getOrNull()
                if (parsed is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    return (parsed as Map<String, Any?>).toJsonElement()
                }
            }
        }
        return value.toJsonElement()
    }

    private fun Map<String, Any?>.toJsonElement(): JsonObject =
        buildJsonObject {
            forEach { (key, value) ->
                put(key, value.toJsonElement())
            }
        }

    private fun Any?.toJsonElement(): JsonElement =
        when (this) {
            null -> JsonNull
            is JsonElement -> this
            is Map<*, *> -> {
                val normalized =
                    buildMap {
                        this@toJsonElement.forEach { (key, value) ->
                            if (key != null) {
                                put(key.toString(), value)
                            }
                        }
                    }
                normalized.toJsonElement()
            }
            is Iterable<*> -> JsonArray(map { it.toJsonElement() })
            is Number -> JsonPrimitive(this)
            is Boolean -> JsonPrimitive(this)
            else -> JsonPrimitive(toString())
        }

    private fun Any.asBoolean(): Boolean? =
        when (this) {
            is Boolean -> this
            else -> toString().toBooleanStrictOrNull()
        }
}
