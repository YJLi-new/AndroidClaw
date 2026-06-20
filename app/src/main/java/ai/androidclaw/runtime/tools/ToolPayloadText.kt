package ai.androidclaw.runtime.tools

internal fun String.toHandoffLine(): String =
    lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" ")
        .ifBlank { "(blank)" }
