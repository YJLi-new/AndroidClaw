package ai.androidclaw.feature.health

internal const val HEALTH_DIAGNOSTICS_NOTICE_MAX_CHARS = 1_000

internal fun boundedHealthDiagnosticsNotice(
    message: String?,
    fallback: String,
): String = (message?.takeIf(String::isNotBlank) ?: fallback).take(HEALTH_DIAGNOSTICS_NOTICE_MAX_CHARS)

