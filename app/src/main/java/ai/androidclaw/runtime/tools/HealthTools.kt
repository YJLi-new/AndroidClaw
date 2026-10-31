package ai.androidclaw.runtime.tools

import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.runtime.providers.NetworkStatusProvider
import ai.androidclaw.runtime.scheduler.SchedulerCoordinator

internal fun healthStatusToolEntry(
    schedulerCoordinator: SchedulerCoordinator,
    settingsDataStore: SettingsDataStore,
    networkStatusProvider: NetworkStatusProvider?,
    providerLabel: suspend () -> String,
    toolCount: () -> Int,
): ToolRegistry.Entry =
    ToolRegistry.Entry(
        descriptor =
            ToolDescriptor(
                name = "health_status",
                aliases = listOf("health.status"),
                description = "AndroidClaw self-check: model provider, network, memory, scheduler permissions.",
                riskTier = ToolRiskTier.ReadOnly,
                allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
            ),
    ) { _, _ ->
        val diagnostics = schedulerCoordinator.diagnostics()
        val notifications = diagnostics.notificationVisibility
        val memoryEnabled = settingsDataStore.memorySettingsSnapshot().enabled
        toolSuccess(
            summary =
                buildString {
                    append("Provider: ").append(providerLabel()).append('.')
                    append("\nNetwork: ").append(networkStatusProvider?.currentStatus()?.summary ?: "unknown").append('.')
                    append("\nMemory: ").append(if (memoryEnabled) "on" else "off").append('.')
                    append("\nExact alarms: ").append(if (diagnostics.exactAlarmGranted) "allowed" else "not allowed").append('.')
                    append("\nNotifications: ")
                    append(
                        if (notifications.runtimePermissionGranted && notifications.appNotificationsEnabled) "allowed" else "blocked",
                    ).append('.')
                    diagnostics.standbyBucket?.let { bucket -> append("\nStandby bucket: ").append(bucket.label).append('.') }
                    append("\nTools: ").append(toolCount()).append('.')
                },
        )
    }
