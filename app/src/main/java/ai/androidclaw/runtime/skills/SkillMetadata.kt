package ai.androidclaw.runtime.skills

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun SkillFrontmatter.skillKey(): String =
    openClawMetadata()
        ?.get("skillKey")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: name

internal fun SkillFrontmatter.primaryEnv(): String? =
    openClawMetadata()
        ?.get("primaryEnv")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }

internal fun SkillFrontmatter.requiredEnvNames(): List<String> =
    openClawMetadata()
        ?.nestedObject("requires")
        ?.stringList("env")
        .orEmpty()

internal fun SkillFrontmatter.requiredConfigPaths(): List<String> =
    openClawMetadata()
        ?.nestedObject("requires")
        ?.stringList("config")
        .orEmpty()

internal fun SkillFrontmatter.declaredSecretNames(): List<String> =
    buildList {
        primaryEnv()?.let(::add)
        addAll(requiredEnvNames())
    }.distinct()

internal fun SkillFrontmatter.declaredConfigPaths(): List<String> = requiredConfigPaths().distinct()

internal fun SkillFrontmatter.requiredBins(): List<String> =
    openClawMetadata()
        ?.nestedObject("requires")
        ?.stringList("bins")
        .orEmpty()

internal fun SkillFrontmatter.requiredAnyBins(): List<String> =
    openClawMetadata()
        ?.nestedObject("requires")
        ?.stringList("anyBins")
        .orEmpty()

internal fun SkillFrontmatter.androidMetadata(): JsonObject? = (metadata as? JsonObject)?.nestedObject("android")

private fun SkillFrontmatter.openClawMetadata(): JsonObject? = (metadata as? JsonObject)?.nestedObject("openclaw")

private fun JsonObject.nestedObject(key: String): JsonObject? = this[key]?.jsonObject

private fun JsonObject.stringList(key: String): List<String> {
    val value = this[key] ?: return emptyList()
    return when (value) {
        is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
        else ->
            value.jsonPrimitive.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?.let(::listOf)
                .orEmpty()
    }
}
