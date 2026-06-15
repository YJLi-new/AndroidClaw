package ai.androidclaw.feature.health

import ai.androidclaw.app.CrashMarkerStore
import ai.androidclaw.app.HealthDependencies
import ai.androidclaw.app.viewModelFactory
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.model.EventLogEntry
import ai.androidclaw.data.repository.EventLogRepository
import ai.androidclaw.runtime.providers.NetworkStatusProvider
import ai.androidclaw.runtime.providers.ProviderRegistry
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator
import ai.androidclaw.runtime.scheduler.SchedulerDiagnostics
import ai.androidclaw.runtime.tools.ToolRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant

data class HealthUiState(
    val providerId: String = "",
    val networkSummary: String = "",
    val providerStatus: String = "",
    val lastProviderIssue: String? = null,
    val lastCrashSummary: String? = null,
    val lastCrashStackTrace: String? = null,
    val bugReportInstructions: String = "",
    val schedulerDiagnostics: SchedulerDiagnostics = SchedulerDiagnostics(),
    val supportedKinds: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val lastSchedulerWake: Instant? = null,
    val lastAutomationResult: String? = null,
    val lastWorkerStopReason: String? = null,
    val recentEvents: List<EventLogEntry> = emptyList(),
)

class HealthViewModel(
    schedulerCoordinator: SchedulerCoordinator,
    toolRegistry: ToolRegistry,
    providerRegistry: ProviderRegistry,
    networkStatusProvider: NetworkStatusProvider,
    settingsDataStore: SettingsDataStore,
    eventLogRepository: EventLogRepository,
    private val crashMarkerStore: CrashMarkerStore,
) : ViewModel() {
    private val uiSharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)
    private val capabilities = schedulerCoordinator.capabilities()
    private val staticTools = toolRegistry.descriptors().map { it.name }.toBoundedHealthUiList()
    private val supportedKinds = capabilities.supportedKinds.toBoundedHealthUiList()
    private val initialDiagnostics = schedulerCoordinator.diagnostics()
    private val diagnosticsRefreshes = MutableStateFlow(0)

    val state: StateFlow<HealthUiState> =
        combine(
            settingsDataStore.settings,
            eventLogRepository.observeRecent(limit = 10),
            diagnosticsRefreshes,
            networkStatusProvider.observeStatus(),
        ) { settings, events, _, networkStatus ->
            val schedulerEvents = events.filter { it.category == EventCategory.Scheduler }
            val providerEvents = events.filter { it.category == EventCategory.Provider }
            val diagnostics = schedulerCoordinator.diagnostics()
            val providerType = settings.providerType
            val lastProviderIssue =
                providerEvents
                    .firstOrNull { it.level != EventLevel.Info }
                    ?: providerEvents.firstOrNull()
            val lastProviderIssueSummary = lastProviderIssue?.let(::formatProviderIssue).toBoundedHealthUiFieldOrNull()
            val crashMarker = crashMarkerStore.read()
            HealthUiState(
                providerId = providerRegistry.require(settings.providerType).id.toBoundedHealthUiField(),
                networkSummary = networkStatus.summary.toBoundedHealthUiField(),
                providerStatus =
                    buildProviderStatus(
                        providerType = providerType,
                        networkStatus = networkStatus,
                        lastProviderIssue = lastProviderIssueSummary,
                    ).toBoundedHealthUiField(),
                lastProviderIssue = lastProviderIssueSummary,
                lastCrashSummary = crashMarker?.let(::buildCrashSummary).toBoundedHealthUiFieldOrNull(),
                lastCrashStackTrace = crashMarker?.stackTrace.toBoundedHealthUiStackTraceOrNull(),
                bugReportInstructions = BUG_REPORT_INSTRUCTIONS.toBoundedHealthUiField(),
                schedulerDiagnostics = diagnostics,
                supportedKinds = supportedKinds,
                tools = staticTools,
                lastSchedulerWake = schedulerEvents.firstOrNull { it.message.contains("started", ignoreCase = true) }?.timestamp,
                lastAutomationResult =
                    schedulerEvents
                        .firstOrNull { event ->
                            event.message.contains("completed", ignoreCase = true) ||
                                event.message.contains("failed", ignoreCase = true) ||
                                event.message.contains("skipped", ignoreCase = true)
                        }?.let { event ->
                            buildString {
                                append(event.message)
                                event.details?.takeIf { it.isNotBlank() }?.let { details ->
                                    append(" (").append(details).append(")")
                                }
                            }
                        }.toBoundedHealthUiDetailsOrNull(),
                lastWorkerStopReason =
                    schedulerEvents
                        .firstOrNull {
                            it.message.contains("stopped", ignoreCase = true)
                        }?.details
                        .toBoundedHealthUiDetailsOrNull(),
                recentEvents = events,
            )
        }.stateIn(
            scope = viewModelScope,
            started = uiSharingStarted,
            initialValue =
                HealthUiState(
                    providerId = providerRegistry.defaultProvider.id.toBoundedHealthUiField(),
                    networkSummary = networkStatusProvider.currentStatus().summary.toBoundedHealthUiField(),
                    providerStatus = "FakeProvider is active. It works fully offline.".toBoundedHealthUiField(),
                    bugReportInstructions = BUG_REPORT_INSTRUCTIONS.toBoundedHealthUiField(),
                    schedulerDiagnostics = initialDiagnostics,
                    supportedKinds = supportedKinds,
                    tools = staticTools,
                ),
        )

    fun refreshDiagnostics() {
        diagnosticsRefreshes.update { it + 1 }
    }

    fun clearCrashNotice() {
        crashMarkerStore.clear()
        refreshDiagnostics()
    }

    companion object {
        fun factory(dependencies: HealthDependencies) =
            viewModelFactory {
                HealthViewModel(
                    schedulerCoordinator = dependencies.schedulerCoordinator,
                    toolRegistry = dependencies.toolRegistry,
                    providerRegistry = dependencies.providerRegistry,
                    networkStatusProvider = dependencies.networkStatusProvider,
                    settingsDataStore = dependencies.settingsDataStore,
                    eventLogRepository = dependencies.eventLogRepository,
                    crashMarkerStore = dependencies.crashMarkerStore,
                )
            }
    }
}

private fun buildProviderStatus(
    providerType: ProviderType,
    networkStatus: ai.androidclaw.runtime.providers.NetworkStatusSnapshot,
    lastProviderIssue: String?,
): String =
    when {
        !providerType.requiresRemoteSettings -> "FakeProvider is active. It works fully offline."
        !networkStatus.isConnected ->
            "No active network connection. Remote provider calls will fail until connectivity returns."
        !networkStatus.isValidated ->
            "Network is connected, but Android has not validated internet access yet."
        !lastProviderIssue.isNullOrBlank() ->
            "Last provider issue: $lastProviderIssue"
        networkStatus.isMetered ->
            "Remote provider calls are using a metered network."
        else -> "Remote provider is ready for interactive use."
    }

private data class ProviderIssueMetadata(
    val kind: String? = null,
    val retryable: Boolean? = null,
    val extras: List<String> = emptyList(),
)

private fun formatProviderIssue(event: EventLogEntry): String {
    val metadata = parseProviderIssueMetadata(event.details)
    return buildString {
        metadata.kind?.let { append(it) }
        metadata.retryable?.let { retryable ->
            if (isNotEmpty()) append(" · ")
            append(if (retryable) "retryable" else "non-retryable")
        }
        if (isNotEmpty()) append(": ")
        append(event.message)
        if (metadata.extras.isNotEmpty()) {
            append(" (").append(metadata.extras.joinToString(" ")).append(")")
        }
    }
}

private fun parseProviderIssueMetadata(details: String?): ProviderIssueMetadata {
    if (details.isNullOrBlank()) {
        return ProviderIssueMetadata()
    }
    var kind: String? = null
    var retryable: Boolean? = null
    val extras = mutableListOf<String>()
    details
        .split(' ')
        .filter { it.isNotBlank() }
        .forEach { token ->
            val parts = token.split('=', limit = 2)
            if (parts.size != 2) {
                extras += token
                return@forEach
            }
            when (parts[0]) {
                "kind" ->
                    kind =
                        parts[1]
                            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                            .replace('_', ' ')
                            .lowercase()
                            .split(' ')
                            .joinToString(" ") { segment ->
                                segment.replaceFirstChar { character -> character.uppercase() }
                            }

                "retryable" -> retryable = parts[1].toBooleanStrictOrNull()
                else -> extras += token
            }
        }
    return ProviderIssueMetadata(
        kind = kind,
        retryable = retryable,
        extras = extras,
    )
}

private fun buildCrashSummary(marker: ai.androidclaw.app.CrashMarker): String =
    buildString {
        append(marker.timestamp)
        append(" · ")
        append(marker.exceptionType.substringAfterLast('.'))
        marker.message?.takeIf { it.isNotBlank() }?.let { message ->
            append(" · ").append(message)
        }
        append(" · thread=").append(marker.threadName)
    }

internal fun List<String>.toBoundedHealthUiList(
    maxItems: Int = HEALTH_UI_LIST_MAX_ITEMS,
    maxItemChars: Int = HEALTH_UI_LIST_ITEM_MAX_CHARS,
): List<String> {
    require(maxItems > 0) { "Health UI list max items must be positive." }
    require(maxItemChars > HEALTH_UI_TRUNCATED_SUFFIX.length) {
        "Health UI list item max must leave room for the truncation suffix."
    }
    if (isEmpty()) {
        return emptyList()
    }
    val visibleLimit =
        if (size > maxItems) {
            maxItems - 1
        } else {
            maxItems
        }.coerceAtLeast(1)
    val visible =
        take(visibleLimit)
            .map { item -> item.toBoundedHealthUiListItem(maxItemChars) }
    val omitted = size - visible.size
    return if (omitted > 0) {
        visible + "(+$omitted omitted)"
    } else {
        visible
    }
}

private fun String.toBoundedHealthUiListItem(maxChars: Int): String {
    val normalized = trim().takeIf(String::isNotBlank) ?: "Unavailable"
    if (normalized.length <= maxChars) {
        return normalized
    }
    val prefixLength = maxChars - HEALTH_UI_TRUNCATED_SUFFIX.length
    return normalized.take(prefixLength).trimEnd() + HEALTH_UI_TRUNCATED_SUFFIX
}

internal fun String.toBoundedHealthUiField(): String =
    toBoundedHealthUiText(
        maxChars = HEALTH_UI_FIELD_MAX_CHARS,
        fallback = "Unavailable",
    )

private fun String?.toBoundedHealthUiFieldOrNull(): String? = toBoundedHealthUiTextOrNull(maxChars = HEALTH_UI_FIELD_MAX_CHARS)

private fun String?.toBoundedHealthUiDetailsOrNull(): String? = toBoundedHealthUiTextOrNull(maxChars = HEALTH_UI_DETAILS_MAX_CHARS)

private fun String?.toBoundedHealthUiStackTraceOrNull(): String? = toBoundedHealthUiTextOrNull(maxChars = HEALTH_UI_STACKTRACE_MAX_CHARS)

private fun String?.toBoundedHealthUiTextOrNull(maxChars: Int): String? =
    this
        ?.toBoundedHealthUiText(
            maxChars = maxChars,
            fallback = "",
        )?.takeIf(String::isNotBlank)

private fun String.toBoundedHealthUiText(
    maxChars: Int,
    fallback: String,
): String {
    require(maxChars > HEALTH_UI_TRUNCATED_SUFFIX.length) {
        "Health UI text max must leave room for the truncation suffix."
    }
    val normalized = trim().takeIf(String::isNotBlank) ?: fallback
    if (normalized.length <= maxChars) {
        return normalized
    }
    val prefixLength = maxChars - HEALTH_UI_TRUNCATED_SUFFIX.length
    return normalized.take(prefixLength).trimEnd() + HEALTH_UI_TRUNCATED_SUFFIX
}

internal const val HEALTH_UI_FIELD_MAX_CHARS = 1_000
internal const val HEALTH_UI_DETAILS_MAX_CHARS = 4_000
internal const val HEALTH_UI_STACKTRACE_MAX_CHARS = 8_000
internal const val HEALTH_UI_LIST_MAX_ITEMS = 50
internal const val HEALTH_UI_LIST_ITEM_MAX_CHARS = 160
private const val HEALTH_UI_TRUNCATED_SUFFIX = "… [truncated]"

private const val BUG_REPORT_INSTRUCTIONS =
    "If AndroidClaw misbehaves, copy diagnostics from this screen and include the exact provider, task, or chat action that failed."
