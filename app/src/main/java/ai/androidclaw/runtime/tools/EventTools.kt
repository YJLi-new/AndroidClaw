package ai.androidclaw.runtime.tools

import ai.androidclaw.data.model.EventCategory
import ai.androidclaw.data.model.EventLevel
import ai.androidclaw.data.model.EventLogEntry
import ai.androidclaw.data.repository.EventLogRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeParseException

internal fun eventToolEntries(
    eventLogRepository: EventLogRepository,
): List<ToolRegistry.Entry> =
    listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.recent",
                    aliases = listOf("event.recent", "logs.recent", "log.recent"),
                    description = "Return bounded recent runtime event logs for local diagnostics.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum event count. Defaults to 20, max 50.",
                            ),
                            ToolArgumentSpec(
                                name = "category",
                                description = "Optional category filter: provider, tool, scheduler, skill, system, or debug.",
                            ),
                            ToolArgumentSpec(
                                name = "level",
                                description = "Optional level filter: info, warn, or error.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDetails",
                                description = "Set true to include bounded event details. Defaults to false.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val requestedLimit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = EVENT_LOG_DEFAULT_LIMIT,
                )
            val limit = requestedLimit.coerceIn(1, EVENT_LOG_MAX_LIMIT)
            val category =
                arguments.optionalText("category")?.let { rawCategory ->
                    parseEventCategory(rawCategory)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.recent received an unknown category.",
                            field = "category",
                            received = rawCategory,
                        )
                }
            val level =
                arguments.optionalText("level")?.let { rawLevel ->
                    parseEventLevel(rawLevel)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.recent received an unknown level.",
                            field = "level",
                            received = rawLevel,
                        )
                }
            val includeDetails = arguments.optionalBoolean("includeDetails")
            val events =
                eventLogRepository
                    .observeRecent(limit = EVENT_LOG_SCAN_LIMIT)
                    .first()
                    .asSequence()
                    .filter { event -> category == null || event.category == category }
                    .filter { event -> level == null || event.level == level }
                    .take(limit)
                    .toList()
            ToolExecutionResult.success(
                summary =
                    if (events.isEmpty()) {
                        "No matching recent events found."
                    } else {
                        "Loaded ${events.size} recent event(s)."
                    },
                payload =
                    buildJsonObject {
                        put("eventCount", events.size)
                        put("recentFirst", true)
                        put("includeDetails", includeDetails)
                        put("category", category?.name ?: "Any")
                        put("level", level?.name ?: "Any")
                        put(
                            "events",
                            buildJsonArray {
                                events.forEach { event ->
                                    add(event.toEventLogPayload(includeDetails = includeDetails))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.get",
                    aliases = listOf("event.get", "logs.get", "log.get"),
                    description = "Return one runtime event log by id for local diagnostics.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "eventId",
                                required = true,
                                description = "Event log identifier.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDetails",
                                description = "Set true to include bounded event details. Defaults to false.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val eventId =
                arguments.optionalText("eventId")
                    ?: return@Entry invalidEventArguments(
                        summary = "events.get requires a non-empty eventId.",
                        field = "eventId",
                        toolName = "events.get",
                    )
            val includeDetails = arguments.optionalBoolean("includeDetails")
            val event =
                eventLogRepository.get(eventId)
                    ?: return@Entry eventNotFoundResult(eventId)
            ToolExecutionResult.success(
                summary = "Loaded event ${event.id}.",
                payload =
                    buildJsonObject {
                        put("event", event.toEventLogPayload(includeDetails = includeDetails))
                        put("includeDetails", includeDetails)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.delete",
                    aliases = listOf("event.delete", "logs.delete", "log.delete", "events.remove", "event.remove"),
                    description = "Delete one local runtime event log by id after explicit confirmation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "eventId",
                                required = true,
                                description = "Event log identifier.",
                            ),
                            ToolArgumentSpec(
                                name = "confirm",
                                required = true,
                                description = "Must be CONFIRM.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val eventId =
                arguments.optionalText("eventId")
                    ?: return@Entry invalidEventArguments(
                        summary = "events.delete requires a non-empty eventId.",
                        field = "eventId",
                        toolName = "events.delete",
                    )
            val event =
                eventLogRepository.get(eventId)
                    ?: return@Entry eventNotFoundResult(eventId)
            if (arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry ToolExecutionResult.failure(
                    summary = "Pass confirm=CONFIRM to delete event ${event.id}.",
                    errorCode = "CONFIRMATION_REQUIRED",
                    payload =
                        buildJsonObject {
                            put("errorCode", "CONFIRMATION_REQUIRED")
                            put("toolName", "events.delete")
                            put("eventId", event.id)
                            put("field", "confirm")
                        },
                )
            }
            val deletedCount = eventLogRepository.delete(event.id)
            ToolExecutionResult.success(
                summary = "Deleted event ${event.id}.",
                payload =
                    buildJsonObject {
                        put("deletedEventId", event.id)
                        put("category", event.category.name)
                        put("level", event.level.name)
                        put("timestampIso", event.timestamp.toString())
                        put("deletedCount", deletedCount)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.search",
                    aliases = listOf("event.search", "logs.search", "log.search"),
                    description = "Search bounded recent runtime event logs for local diagnostics.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "query",
                                required = true,
                                description = "Text to search across event ids, categories, levels, messages, and details.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum event count. Defaults to 20, max 50.",
                            ),
                            ToolArgumentSpec(
                                name = "category",
                                description = "Optional category filter: provider, tool, scheduler, skill, system, or debug.",
                            ),
                            ToolArgumentSpec(
                                name = "level",
                                description = "Optional level filter: info, warn, or error.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDetails",
                                description = "Set true to include bounded event details. Defaults to false.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val query =
                arguments.optionalText("query")
                    ?: return@Entry invalidEventArguments(
                        summary = "events.search requires a non-empty query.",
                        field = "query",
                        toolName = "events.search",
                    )
            val requestedLimit =
                arguments.optionalInt(
                    field = "limit",
                    defaultValue = EVENT_LOG_DEFAULT_LIMIT,
                )
            val limit = requestedLimit.coerceIn(1, EVENT_LOG_MAX_LIMIT)
            val category =
                arguments.optionalText("category")?.let { rawCategory ->
                    parseEventCategory(rawCategory)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.search received an unknown category.",
                            field = "category",
                            received = rawCategory,
                            toolName = "events.search",
                        )
                }
            val level =
                arguments.optionalText("level")?.let { rawLevel ->
                    parseEventLevel(rawLevel)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.search received an unknown level.",
                            field = "level",
                            received = rawLevel,
                            toolName = "events.search",
                        )
                }
            val includeDetails = arguments.optionalBoolean("includeDetails")
            val events =
                eventLogRepository
                    .observeRecent(limit = EVENT_LOG_SCAN_LIMIT)
                    .first()
                    .asSequence()
                    .filter { event -> category == null || event.category == category }
                    .filter { event -> level == null || event.level == level }
                    .filter { event -> event.matchesEventQuery(query) }
                    .take(limit)
                    .toList()
            ToolExecutionResult.success(
                summary =
                    if (events.isEmpty()) {
                        "No events matched \"$query\"."
                    } else {
                        "Found ${events.size} event(s) matching \"$query\"."
                    },
                payload =
                    buildJsonObject {
                        put("query", query)
                        put("eventCount", events.size)
                        put("recentFirst", true)
                        put("includeDetails", includeDetails)
                        put("category", category?.name ?: "Any")
                        put("level", level?.name ?: "Any")
                        put(
                            "events",
                            buildJsonArray {
                                events.forEach { event ->
                                    add(event.toEventLogPayload(includeDetails = includeDetails))
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.stats",
                    aliases = listOf("event.stats", "logs.stats", "log.stats"),
                    description = "Return aggregate counts for recent runtime event logs without event details.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "scanLimit",
                                description = "Maximum recent event count to scan. Defaults to 200, max 500.",
                            ),
                            ToolArgumentSpec(
                                name = "category",
                                description = "Optional category filter: provider, tool, scheduler, skill, system, or debug.",
                            ),
                            ToolArgumentSpec(
                                name = "level",
                                description = "Optional level filter: info, warn, or error.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val requestedScanLimit =
                arguments.optionalInt(
                    field = "scanLimit",
                    defaultValue = EVENT_LOG_SCAN_LIMIT,
                )
            val scanLimit = requestedScanLimit.coerceIn(1, EVENT_LOG_STATS_MAX_SCAN_LIMIT)
            val category =
                arguments.optionalText("category")?.let { rawCategory ->
                    parseEventCategory(rawCategory)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.stats received an unknown category.",
                            field = "category",
                            received = rawCategory,
                            toolName = "events.stats",
                        )
                }
            val level =
                arguments.optionalText("level")?.let { rawLevel ->
                    parseEventLevel(rawLevel)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.stats received an unknown level.",
                            field = "level",
                            received = rawLevel,
                            toolName = "events.stats",
                        )
                }
            val scannedEvents =
                eventLogRepository
                    .observeRecent(limit = scanLimit)
                    .first()
            val matchingEvents =
                scannedEvents
                    .asSequence()
                    .filter { event -> category == null || event.category == category }
                    .filter { event -> level == null || event.level == level }
                    .toList()
            ToolExecutionResult.success(
                summary =
                    if (matchingEvents.isEmpty()) {
                        "No matching recent events found in $scanLimit scanned event(s)."
                    } else {
                        "Summarized ${matchingEvents.size} matching event(s)."
                    },
                payload =
                    buildJsonObject {
                        put("scanLimit", scanLimit)
                        put("scannedEventCount", scannedEvents.size)
                        put("matchedEventCount", matchingEvents.size)
                        put("recentFirst", true)
                        put("category", category?.name ?: "Any")
                        put("level", level?.name ?: "Any")
                        put(
                            "newestEventAtIso",
                            matchingEvents
                                .firstOrNull()
                                ?.timestamp
                                ?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                        )
                        put(
                            "oldestEventAtIso",
                            matchingEvents
                                .lastOrNull()
                                ?.timestamp
                                ?.let { JsonPrimitive(it.toString()) } ?: JsonNull,
                        )
                        put("countsByCategory", matchingEvents.toEventCategoryCountsPayload())
                        put("countsByLevel", matchingEvents.toEventLevelCountsPayload())
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.export",
                    aliases =
                        listOf(
                            "event.export",
                            "logs.export",
                            "log.export",
                            "events.backup",
                            "logs.backup",
                            "diagnostics.export",
                        ),
                    description = "Export bounded recent runtime diagnostics with stable format metadata.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum event count to export. Defaults to 50, max 100.",
                            ),
                            ToolArgumentSpec(
                                name = "scanLimit",
                                description = "Maximum recent event count to scan before filtering. Defaults to 200, max 500.",
                            ),
                            ToolArgumentSpec(
                                name = "category",
                                description = "Optional category filter: provider, tool, scheduler, skill, system, or debug.",
                            ),
                            ToolArgumentSpec(
                                name = "level",
                                description = "Optional level filter: info, warn, or error.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDetails",
                                description = "Set true to include bounded event details. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit exportMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = EVENT_EXPORT_DEFAULT_LIMIT,
                    ).coerceIn(0, EVENT_EXPORT_MAX_LIMIT)
            val scanLimit =
                arguments
                    .optionalInt(
                        field = "scanLimit",
                        defaultValue = EVENT_LOG_SCAN_LIMIT,
                    ).coerceIn(1, EVENT_LOG_STATS_MAX_SCAN_LIMIT)
            val category =
                arguments.optionalText("category")?.let { rawCategory ->
                    parseEventCategory(rawCategory)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.export received an unknown category.",
                            field = "category",
                            received = rawCategory,
                            toolName = "events.export",
                        )
                }
            val level =
                arguments.optionalText("level")?.let { rawLevel ->
                    parseEventLevel(rawLevel)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.export received an unknown level.",
                            field = "level",
                            received = rawLevel,
                            toolName = "events.export",
                        )
                }
            val includeDetails = arguments.optionalBoolean("includeDetails")
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val totalEventCount = eventLogRepository.count()
            val scannedEvents =
                eventLogRepository
                    .observeRecent(limit = scanLimit)
                    .first()
            val matchingEvents =
                scannedEvents
                    .asSequence()
                    .filter { event -> category == null || event.category == category }
                    .filter { event -> level == null || event.level == level }
                    .toList()
            val exportedEvents = matchingEvents.take(limit)
            val exportMarkdown =
                if (includeMarkdown) {
                    exportedEvents.toEventExportMarkdown(
                        totalEventCount = totalEventCount,
                        scannedEventCount = scannedEvents.size,
                        matchedEventCount = matchingEvents.size,
                        category = category,
                        level = level,
                        limit = limit,
                        includeDetails = includeDetails,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    if (exportedEvents.isEmpty()) {
                        "Prepared empty event diagnostics export."
                    } else {
                        "Prepared event diagnostics export with ${exportedEvents.size} event(s)."
                    },
                payload =
                    buildJsonObject {
                        put("exportFormat", EVENT_EXPORT_FORMAT)
                        put("exportVersion", EVENT_EXPORT_VERSION)
                        put("totalEventCount", totalEventCount)
                        put("scanLimit", scanLimit)
                        put("scannedEventCount", scannedEvents.size)
                        put("matchedEventCount", matchingEvents.size)
                        put("eventCount", exportedEvents.size)
                        put("exportedEventCount", exportedEvents.size)
                        put("omittedEventCount", (matchingEvents.size - exportedEvents.size).coerceAtLeast(0))
                        put("recentFirst", true)
                        put("category", category?.name ?: "Any")
                        put("level", level?.name ?: "Any")
                        put("limit", limit)
                        put("includeDetails", includeDetails)
                        put("detailsIncluded", includeDetails)
                        put("secretValuesIncluded", false)
                        put("apiKeyValuesIncluded", false)
                        put("oauthTokenValuesIncluded", false)
                        put("providerMetaIncluded", false)
                        put("messageBodiesIncluded", false)
                        put("countsByCategory", matchingEvents.toEventCategoryCountsPayload())
                        put("countsByLevel", matchingEvents.toEventLevelCountsPayload())
                        put(
                            "events",
                            buildJsonArray {
                                exportedEvents.forEach { event ->
                                    add(event.toEventLogPayload(includeDetails = includeDetails))
                                }
                            },
                        )
                        put("includeMarkdown", includeMarkdown)
                        put("exportMarkdown", exportMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.import",
                    aliases =
                        listOf(
                            "event.import",
                            "logs.import",
                            "log.import",
                            "diagnostics.import",
                            "events.restore",
                            "logs.restore",
                        ),
                    description = "Import bounded event diagnostics exported by events.export.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "events",
                                description = "Array of exported event objects, or pass export.events.",
                            ),
                            ToolArgumentSpec(
                                name = "export",
                                description = "Optional events.export payload containing an events array.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum events to scan. Defaults to 50, max 100.",
                            ),
                            ToolArgumentSpec(
                                name = "importDetails",
                                description = "Set true to import bounded event details. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "includeDetails",
                                description = "Set true to include imported details in result payload. Defaults to importDetails.",
                            ),
                            ToolArgumentSpec(
                                name = "dryRun",
                                description = "Set true to preview importable events without writing. Defaults to false.",
                            ),
                            ToolArgumentSpec(
                                name = "confirm",
                                description = "Must be CONFIRM unless dryRun=true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val dryRun = arguments.optionalBoolean("dryRun", defaultValue = false)
            if (!dryRun && arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry missingEventImportConfirmationResult()
            }
            val rawEntries =
                when (val parsedEntries = arguments.eventImportEntries()) {
                    is EventImportEntriesParseResult.Failure -> return@Entry parsedEntries.result
                    is EventImportEntriesParseResult.Success -> parsedEntries.entries
                }
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = EVENT_IMPORT_DEFAULT_LIMIT,
                    ).coerceIn(0, EVENT_IMPORT_MAX_LIMIT)
            val importDetails = arguments.optionalBoolean("importDetails", defaultValue = false)
            val includeDetails = arguments.optionalBoolean("includeDetails", defaultValue = importDetails)
            val scannedEntries = rawEntries.take(limit)
            val candidates = mutableListOf<EventImportCandidate>()
            val skipped = mutableListOf<EventImportSkippedEntry>()
            scannedEntries.forEachIndexed { sourceIndex, element ->
                when (val parsedCandidate = element.toEventImportCandidate(sourceIndex = sourceIndex)) {
                    is EventImportCandidateParseResult.Candidate -> candidates += parsedCandidate.candidate
                    is EventImportCandidateParseResult.Skipped -> skipped += parsedCandidate.skipped
                }
            }
            val importedEvents =
                if (dryRun) {
                    emptyList()
                } else {
                    candidates.map { candidate ->
                        EventImportedItem(
                            candidate = candidate,
                            event =
                                eventLogRepository.log(
                                    category = candidate.category,
                                    level = candidate.level,
                                    message = candidate.message,
                                    details = candidate.details.takeIf { importDetails },
                                ),
                            detailsImported = importDetails && !candidate.details.isNullOrBlank(),
                        )
                    }
                }
            val eventCountAfter = eventLogRepository.count()
            ToolExecutionResult.success(
                summary =
                    if (dryRun) {
                        "Prepared dry-run event diagnostics import with ${candidates.size} importable event(s)."
                    } else {
                        "Imported ${importedEvents.size} event diagnostic(s); skipped ${skipped.size}."
                    },
                payload =
                    buildJsonObject {
                        put("importFormat", EVENT_IMPORT_FORMAT)
                        put("importVersion", EVENT_IMPORT_VERSION)
                        put("acceptedExportFormat", EVENT_EXPORT_FORMAT)
                        put("acceptedExportVersion", EVENT_EXPORT_VERSION)
                        put("eventLimit", limit)
                        put("importLimit", limit)
                        put("dryRun", dryRun)
                        put("importDetails", importDetails)
                        put("includeDetails", includeDetails)
                        put("detailsImported", importDetails)
                        put("detailsIncluded", includeDetails && importDetails)
                        put("secretValuesIncluded", false)
                        put("apiKeyValuesIncluded", false)
                        put("oauthTokenValuesIncluded", false)
                        put("providerMetaImported", false)
                        put("providerMetaIncluded", false)
                        put("messageBodiesImported", false)
                        put("messageBodiesIncluded", false)
                        put("sourceEventIdsPreserved", false)
                        put("sourceTimestampsPreserved", false)
                        put("receivedEventCount", rawEntries.size)
                        put("scannedEventCount", scannedEntries.size)
                        put("omittedInputEventCount", (rawEntries.size - scannedEntries.size).coerceAtLeast(0))
                        put("importableEventCount", candidates.size)
                        put("importedEventCount", importedEvents.size)
                        put("skippedEventCount", skipped.size)
                        put("invalidEventCount", skipped.count { entry -> entry.code.startsWith("events.import.invalid") })
                        put("detailsImportableCount", candidates.count { candidate -> !candidate.details.isNullOrBlank() })
                        put("detailsImportedCount", importedEvents.count { imported -> imported.detailsImported })
                        put("eventCountAfter", eventCountAfter)
                        put(
                            "importableEvents",
                            buildJsonArray {
                                candidates.forEach { candidate ->
                                    add(
                                        candidate.toEventImportCandidatePayload(
                                            includeDetails = includeDetails && importDetails,
                                            importDetails = importDetails,
                                        ),
                                    )
                                }
                            },
                        )
                        put(
                            "importedEvents",
                            buildJsonArray {
                                importedEvents.forEach { imported ->
                                    add(imported.toEventImportedPayload(includeDetails = includeDetails && importDetails))
                                }
                            },
                        )
                        put(
                            "skippedEvents",
                            buildJsonArray {
                                skipped.forEach { skippedEvent ->
                                    add(skippedEvent.toEventImportSkippedPayload())
                                }
                            },
                        )
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.handoff",
                    aliases =
                        listOf(
                            "event.handoff",
                            "logs.handoff",
                            "log.handoff",
                            "events.snapshot",
                            "event.snapshot",
                            "logs.snapshot",
                            "log.snapshot",
                        ),
                    description = "Return a compact event-log handoff without raw event details.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum recent event count to include. Defaults to 12, max 50.",
                            ),
                            ToolArgumentSpec(
                                name = "category",
                                description = "Optional category filter: provider, tool, scheduler, skill, system, or debug.",
                            ),
                            ToolArgumentSpec(
                                name = "level",
                                description = "Optional level filter: info, warn, or error.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit handoffMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = EVENT_HANDOFF_DEFAULT_LIMIT,
                    ).coerceIn(0, EVENT_HANDOFF_MAX_LIMIT)
            val category =
                arguments.optionalText("category")?.let { rawCategory ->
                    parseEventCategory(rawCategory)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.handoff received an unknown category.",
                            field = "category",
                            received = rawCategory,
                            toolName = "events.handoff",
                        )
                }
            val level =
                arguments.optionalText("level")?.let { rawLevel ->
                    parseEventLevel(rawLevel)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.handoff received an unknown level.",
                            field = "level",
                            received = rawLevel,
                            toolName = "events.handoff",
                        )
                }
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val totalEventCount = eventLogRepository.count()
            val matchingEvents =
                eventLogRepository
                    .observeRecent(limit = EVENT_LOG_SCAN_LIMIT)
                    .first()
                    .asSequence()
                    .filter { event -> category == null || event.category == category }
                    .filter { event -> level == null || event.level == level }
                    .toList()
            val includedEvents = matchingEvents.take(limit)
            val handoffMarkdown =
                if (includeMarkdown) {
                    includedEvents.toEventHandoffMarkdown(
                        totalEventCount = totalEventCount,
                        matchedEventCount = matchingEvents.size,
                        category = category,
                        level = level,
                        limit = limit,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    if (includedEvents.isEmpty()) {
                        "Prepared empty event handoff."
                    } else {
                        "Prepared event handoff with ${includedEvents.size} recent event(s)."
                    },
                payload =
                    buildJsonObject {
                        put("totalEventCount", totalEventCount)
                        put("matchedEventCount", matchingEvents.size)
                        put("eventCount", includedEvents.size)
                        put("omittedEventCount", (matchingEvents.size - includedEvents.size).coerceAtLeast(0))
                        put("recentFirst", true)
                        put("detailsIncluded", false)
                        put("category", category?.name ?: "Any")
                        put("level", level?.name ?: "Any")
                        put("limit", limit)
                        put("includeMarkdown", includeMarkdown)
                        put("countsByCategory", matchingEvents.toEventCategoryCountsPayload())
                        put("countsByLevel", matchingEvents.toEventLevelCountsPayload())
                        put(
                            "events",
                            buildJsonArray {
                                includedEvents.forEach { event ->
                                    add(event.toEventHandoffPayload())
                                }
                            },
                        )
                        put("handoffMarkdown", handoffMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.doctor",
                    aliases =
                        listOf(
                            "event.doctor",
                            "logs.doctor",
                            "log.doctor",
                            "events.health",
                            "event.health",
                            "logs.health",
                            "log.health",
                            "events.check",
                            "event.check",
                            "logs.check",
                            "log.check",
                        ),
                    description = "Return actionable event-log diagnostics without raw event details.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "scanLimit",
                                description = "Maximum recent event count to scan. Defaults to 200, max 500.",
                            ),
                            ToolArgumentSpec(
                                name = "limit",
                                description = "Maximum diagnostic issues and event checks to include. Defaults to 20, max 50.",
                            ),
                            ToolArgumentSpec(
                                name = "category",
                                description = "Optional category filter: provider, tool, scheduler, skill, system, or debug.",
                            ),
                            ToolArgumentSpec(
                                name = "includeMarkdown",
                                description = "Set false to omit doctorMarkdown. Defaults to true.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val scanLimit =
                arguments
                    .optionalInt(
                        field = "scanLimit",
                        defaultValue = EVENT_LOG_SCAN_LIMIT,
                    ).coerceIn(1, EVENT_LOG_STATS_MAX_SCAN_LIMIT)
            val limit =
                arguments
                    .optionalInt(
                        field = "limit",
                        defaultValue = EVENT_DOCTOR_DEFAULT_LIMIT,
                    ).coerceIn(0, EVENT_DOCTOR_MAX_LIMIT)
            val category =
                arguments.optionalText("category")?.let { rawCategory ->
                    parseEventCategory(rawCategory)
                        ?: return@Entry invalidEventArguments(
                            summary = "events.doctor received an unknown category.",
                            field = "category",
                            received = rawCategory,
                            toolName = "events.doctor",
                        )
                }
            val includeMarkdown = arguments.optionalBoolean("includeMarkdown", defaultValue = true)
            val totalEventCount = eventLogRepository.count()
            val scannedEvents =
                eventLogRepository
                    .observeRecent(limit = scanLimit)
                    .first()
            val matchingEvents =
                scannedEvents
                    .filter { event -> category == null || event.category == category }
            val issues = matchingEvents.toEventDoctorIssues()
            val includedIssues = issues.take(limit)
            val includedChecks = matchingEvents.take(limit)
            val status = issues.toEventDoctorStatus()
            val doctorMarkdown =
                if (includeMarkdown) {
                    includedIssues.toEventDoctorMarkdown(
                        status = status,
                        totalEventCount = totalEventCount,
                        scannedEventCount = scannedEvents.size,
                        matchedEventCount = matchingEvents.size,
                        issueCount = issues.size,
                        category = category,
                        limit = limit,
                    )
                } else {
                    null
                }
            ToolExecutionResult.success(
                summary =
                    when {
                        issues.isEmpty() ->
                            "Event doctor found no warning or error events in ${matchingEvents.size} matching event(s)."
                        includedIssues.size == issues.size ->
                            "Event doctor found ${issues.size} issue event(s) in ${matchingEvents.size} matching event(s)."
                        else ->
                            "Event doctor found ${issues.size} issue event(s) and included ${includedIssues.size}."
                    },
                payload =
                    buildJsonObject {
                        put("status", status)
                        put("totalEventCount", totalEventCount)
                        put("scanLimit", scanLimit)
                        put("scannedEventCount", scannedEvents.size)
                        put("matchedEventCount", matchingEvents.size)
                        put("category", category?.name ?: "Any")
                        put("limit", limit)
                        put("eventCheckCount", includedChecks.size)
                        put("eventChecksOmitted", (matchingEvents.size - includedChecks.size).coerceAtLeast(0))
                        put("issueCount", issues.size)
                        put("includedIssueCount", includedIssues.size)
                        put("omittedIssueCount", (issues.size - includedIssues.size).coerceAtLeast(0))
                        put("errorCount", issues.count { issue -> issue.severity == "Error" })
                        put("warningCount", issues.count { issue -> issue.severity == "Warning" })
                        put("includeMarkdown", includeMarkdown)
                        put("detailsIncluded", false)
                        put("countsByCategory", matchingEvents.toEventCategoryCountsPayload())
                        put("countsByLevel", matchingEvents.toEventLevelCountsPayload())
                        put(
                            "eventChecks",
                            buildJsonArray {
                                includedChecks.forEach { event ->
                                    add(event.toEventDoctorCheckPayload())
                                }
                            },
                        )
                        put(
                            "issues",
                            buildJsonArray {
                                includedIssues.forEach { issue ->
                                    add(issue.toEventDoctorPayload())
                                }
                            },
                        )
                        put("doctorMarkdown", doctorMarkdown?.let(::JsonPrimitive) ?: JsonNull)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.clear",
                    aliases = listOf("event.clear", "logs.clear", "log.clear"),
                    description = "Delete all local event logs after explicit confirmation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "confirm",
                                required = true,
                                description = "Must be CONFIRM.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            if (arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry ToolExecutionResult.failure(
                    summary = "Pass confirm=CONFIRM to clear local event logs.",
                    errorCode = "CONFIRMATION_REQUIRED",
                    payload =
                        buildJsonObject {
                            put("errorCode", "CONFIRMATION_REQUIRED")
                            put("toolName", "events.clear")
                            put("field", "confirm")
                        },
                )
            }
            val deletedCount = eventLogRepository.clearAll()
            ToolExecutionResult.success(
                summary = "Cleared $deletedCount event log(s).",
                payload =
                    buildJsonObject {
                        put("deletedCount", deletedCount)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "events.trim",
                    aliases = listOf("event.trim", "logs.trim", "log.trim"),
                    description = "Delete local event logs older than an ISO-8601 cutoff after explicit confirmation.",
                    arguments =
                        listOf(
                            ToolArgumentSpec(
                                name = "olderThanIso",
                                required = true,
                                description = "ISO-8601 cutoff. Events before this instant are deleted.",
                            ),
                            ToolArgumentSpec(
                                name = "confirm",
                                required = true,
                                description = "Must be CONFIRM.",
                            ),
                        ),
                ),
        ) { _, arguments ->
            val olderThanIso =
                arguments.optionalText("olderThanIso")
                    ?: return@Entry invalidEventArguments(
                        summary = "events.trim requires a non-empty olderThanIso.",
                        field = "olderThanIso",
                        toolName = "events.trim",
                    )
            if (arguments.optionalText("confirm") != "CONFIRM") {
                return@Entry ToolExecutionResult.failure(
                    summary = "Pass confirm=CONFIRM to trim old event logs.",
                    errorCode = "MISSING_TRIM_CONFIRMATION",
                    payload =
                        buildJsonObject {
                            put("errorCode", "MISSING_TRIM_CONFIRMATION")
                            put("toolName", "events.trim")
                        },
                )
            }
            val cutoff =
                try {
                    Instant.parse(olderThanIso)
                } catch (_: DateTimeParseException) {
                    return@Entry invalidEventArguments(
                        summary = "events.trim received an invalid olderThanIso.",
                        field = "olderThanIso",
                        received = olderThanIso,
                        toolName = "events.trim",
                    )
                }
            val deletedCount = eventLogRepository.trimOlderThan(cutoff)
            ToolExecutionResult.success(
                summary = "Trimmed $deletedCount event log(s) older than $cutoff.",
                payload =
                    buildJsonObject {
                        put("olderThanIso", cutoff.toString())
                        put("deletedCount", deletedCount)
                    },
            )
        },
    )

private fun parseEventCategory(rawCategory: String): EventCategory? =
    when (rawCategory.trim().lowercase()) {
        "provider" -> EventCategory.Provider
        "tool" -> EventCategory.Tool
        "scheduler" -> EventCategory.Scheduler
        "skill" -> EventCategory.Skill
        "system" -> EventCategory.System
        "debug" -> EventCategory.Debug
        else -> null
    }

private fun parseEventLevel(rawLevel: String): EventLevel? =
    when (rawLevel.trim().lowercase()) {
        "info" -> EventLevel.Info
        "warn", "warning" -> EventLevel.Warn
        "error" -> EventLevel.Error
        else -> null
    }

private fun invalidEventArguments(
    summary: String,
    field: String,
    received: String? = null,
    toolName: String = "events.recent",
): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = summary,
        errorCode = "INVALID_ARGUMENTS",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_ARGUMENTS")
                put("toolName", toolName)
                put("field", field)
                received?.let { value ->
                    put("received", value.take(EVENT_LOG_FILTER_MAX_CHARS))
                }
            },
    )

private fun eventNotFoundResult(eventId: String): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Event $eventId was not found.",
        errorCode = "EVENT_NOT_FOUND",
        payload =
            buildJsonObject {
                put("errorCode", "EVENT_NOT_FOUND")
                put("eventId", eventId)
            },
    )

private fun EventLogEntry.matchesEventQuery(query: String): Boolean {
    val normalizedQuery = query.lowercase()
    return buildList {
        add(id)
        add(category.name)
        add(level.name)
        add(message)
        details?.let(::add)
    }.any { value -> value.lowercase().contains(normalizedQuery) }
}

private fun List<EventLogEntry>.toEventCategoryCountsPayload(): JsonArray =
    buildJsonArray {
        listOf(
            EventCategory.Provider,
            EventCategory.Tool,
            EventCategory.Scheduler,
            EventCategory.Skill,
            EventCategory.System,
            EventCategory.Debug,
        ).forEach { category ->
            val count = count { event -> event.category == category }
            if (count > 0) {
                add(
                    buildJsonObject {
                        put("category", category.name)
                        put("count", count)
                    },
                )
            }
        }
    }

private fun List<EventLogEntry>.toEventLevelCountsPayload(): JsonArray =
    buildJsonArray {
        listOf(
            EventLevel.Info,
            EventLevel.Warn,
            EventLevel.Error,
        ).forEach { level ->
            val count = count { event -> event.level == level }
            if (count > 0) {
                add(
                    buildJsonObject {
                        put("level", level.name)
                        put("count", count)
                    },
                )
            }
        }
    }


private fun JsonObject.eventOptionalSourceId(field: String): String? =
    optionalText(field)
        ?.take(EVENT_LOG_FILTER_MAX_CHARS)
        ?.ifBlank { null }

private fun JsonObject.eventImportEntries(): EventImportEntriesParseResult {
    val directEntries = this["events"]
    val exportEntries = (this["export"] as? JsonObject)?.get("events")
    val payloadEntries = (this["payload"] as? JsonObject)?.get("events")
    val entries =
        directEntries ?: exportEntries ?: payloadEntries ?: return EventImportEntriesParseResult.Failure(
            missingEventImportEntriesResult(),
        )
    return (entries as? JsonArray)?.let(EventImportEntriesParseResult::Success)
        ?: EventImportEntriesParseResult.Failure(invalidEventImportEntriesResult())
}

private fun JsonElement.toEventImportCandidate(sourceIndex: Int): EventImportCandidateParseResult {
    val objectValue =
        this as? JsonObject ?: return eventImportSkipped(
            sourceIndex = sourceIndex,
            code = "events.import.invalid_entry",
            summary = "Import entry must be an event object.",
        )
    val rawCategory =
        objectValue.optionalText("category")
            ?: return eventImportSkipped(
                sourceIndex = sourceIndex,
                code = "events.import.invalid_missing_category",
                summary = "Import entry skipped because category is missing.",
            )
    val category =
        parseEventCategory(rawCategory)
            ?: return eventImportSkipped(
                sourceIndex = sourceIndex,
                code = "events.import.invalid_category",
                summary = "Import entry skipped because category is unsupported.",
            )
    val rawLevel =
        objectValue.optionalText("level")
            ?: return eventImportSkipped(
                sourceIndex = sourceIndex,
                code = "events.import.invalid_missing_level",
                summary = "Import entry skipped because level is missing.",
            )
    val level =
        parseEventLevel(rawLevel)
            ?: return eventImportSkipped(
                sourceIndex = sourceIndex,
                code = "events.import.invalid_level",
                summary = "Import entry skipped because level is unsupported.",
            )
    val message =
        objectValue.optionalRawText("message")
            ?: objectValue.optionalRawText("summary")
            ?: return eventImportSkipped(
                sourceIndex = sourceIndex,
                code = "events.import.invalid_missing_message",
                summary = "Import entry skipped because message is missing or blank.",
            )
    return EventImportCandidateParseResult.Candidate(
        EventImportCandidate(
            sourceIndex = sourceIndex,
            sourceEventId =
                objectValue.eventOptionalSourceId("sourceEventId")
                    ?: objectValue.eventOptionalSourceId("eventId")
                    ?: objectValue.eventOptionalSourceId("id"),
            sourceTimestampIso =
                objectValue.optionalText("timestampIso")
                    ?: objectValue.optionalText("timestamp"),
            category = category,
            level = level,
            message = message,
            details = objectValue.optionalRawText("details"),
        ),
    )
}

private fun eventImportSkipped(
    sourceIndex: Int,
    code: String,
    summary: String,
): EventImportCandidateParseResult.Skipped =
    EventImportCandidateParseResult.Skipped(
        EventImportSkippedEntry(
            sourceIndex = sourceIndex,
            code = code,
            summary = summary,
        ),
    )

private fun missingEventImportConfirmationResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Pass confirm=CONFIRM to import events, or dryRun=true to preview without writing.",
        errorCode = "MISSING_EVENT_IMPORT_CONFIRMATION",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_EVENT_IMPORT_CONFIRMATION")
                put("field", "confirm")
            },
    )

private fun missingEventImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Provide an events array or an export object containing events to import.",
        errorCode = "MISSING_EVENT_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "MISSING_EVENT_IMPORT_ENTRIES")
                put("field", "events")
            },
    )

private fun invalidEventImportEntriesResult(): ToolExecutionResult =
    ToolExecutionResult.failure(
        summary = "Event import entries must be an array.",
        errorCode = "INVALID_EVENT_IMPORT_ENTRIES",
        payload =
            buildJsonObject {
                put("errorCode", "INVALID_EVENT_IMPORT_ENTRIES")
                put("field", "events")
            },
    )

private fun EventImportCandidate.toEventImportCandidatePayload(
    includeDetails: Boolean,
    importDetails: Boolean,
): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("sourceEventId", sourceEventId?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceTimestampIso", sourceTimestampIso?.let(::JsonPrimitive) ?: JsonNull)
        put("category", category.name)
        put("level", level.name)
        put("message", message.take(EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS))
        put("messageTruncated", message.length > EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS)
        put(
            "details",
            if (includeDetails) {
                details
                    ?.take(EVENT_LOG_DETAILS_PAYLOAD_MAX_CHARS)
                    ?.let(::JsonPrimitive)
                    ?: JsonNull
            } else {
                JsonNull
            },
        )
        put("detailsImported", importDetails && !details.isNullOrBlank())
        put("detailsIncluded", includeDetails)
        put("sourceEventIdPreserved", false)
        put("sourceTimestampPreserved", false)
        put("providerMetaImported", false)
        put("providerMetaIncluded", false)
    }

private fun EventImportedItem.toEventImportedPayload(includeDetails: Boolean): JsonObject =
    buildJsonObject {
        put("sourceIndex", candidate.sourceIndex)
        put("sourceEventId", candidate.sourceEventId?.let(::JsonPrimitive) ?: JsonNull)
        put("newEventId", event.id)
        put("eventId", event.id)
        put("sourceTimestampIso", candidate.sourceTimestampIso?.let(::JsonPrimitive) ?: JsonNull)
        put("timestampIso", event.timestamp.toString())
        put("category", event.category.name)
        put("level", event.level.name)
        put("message", event.message.take(EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS))
        put("messageTruncated", event.message.length > EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS)
        put(
            "details",
            if (includeDetails) {
                event.details
                    ?.take(EVENT_LOG_DETAILS_PAYLOAD_MAX_CHARS)
                    ?.let(::JsonPrimitive)
                    ?: JsonNull
            } else {
                JsonNull
            },
        )
        put("detailsImported", detailsImported)
        put("detailsIncluded", includeDetails)
        put("sourceEventIdPreserved", false)
        put("sourceTimestampPreserved", false)
        put("providerMetaImported", false)
        put("providerMetaIncluded", false)
    }

private fun EventImportSkippedEntry.toEventImportSkippedPayload(): JsonObject =
    buildJsonObject {
        put("sourceIndex", sourceIndex)
        put("code", code)
        put("summary", summary)
    }

private fun EventLogEntry.toEventLogPayload(includeDetails: Boolean): JsonObject =
    buildJsonObject {
        put("id", id)
        put("timestampIso", timestamp.toString())
        put("category", category.name)
        put("level", level.name)
        put("message", message.take(EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS))
        put("messageTruncated", message.length > EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS)
        if (includeDetails) {
            val boundedDetails = details?.take(EVENT_LOG_DETAILS_PAYLOAD_MAX_CHARS)
            put("details", boundedDetails?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "detailsTruncated",
                details?.let { it.length > EVENT_LOG_DETAILS_PAYLOAD_MAX_CHARS } ?: false,
            )
        }
    }

private fun EventLogEntry.toEventHandoffPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("timestampIso", timestamp.toString())
        put("category", category.name)
        put("level", level.name)
        put("message", message.take(EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS))
        put("messageTruncated", message.length > EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS)
        put("hasDetails", details != null)
        put("detailsIncluded", false)
    }

private fun List<EventLogEntry>.toEventExportMarkdown(
    totalEventCount: Int,
    scannedEventCount: Int,
    matchedEventCount: Int,
    category: EventCategory?,
    level: EventLevel?,
    limit: Int,
    includeDetails: Boolean,
): String {
    val exportedEvents = this
    return buildString {
        appendLine("# Event diagnostics export")
        appendLine()
        appendLine("- Format: $EVENT_EXPORT_FORMAT")
        appendLine("- Version: $EVENT_EXPORT_VERSION")
        appendLine("- Total event logs: $totalEventCount")
        appendLine("- Scanned events: $scannedEventCount")
        appendLine("- Matching events: $matchedEventCount")
        appendLine("- Events exported: ${exportedEvents.size} of up to $limit")
        appendLine("- Category filter: ${category?.name ?: "Any"}")
        appendLine("- Level filter: ${level?.name ?: "Any"}")
        appendLine("- Recent first: true")
        appendLine("- Event details included: $includeDetails")
        appendLine("- Secret values included: false")
        appendLine("- Provider metadata included: false")
        appendLine()
        appendLine("## Events")
        if (exportedEvents.isEmpty()) {
            appendLine("_No events exported._")
        } else {
            exportedEvents.forEach { event ->
                append("- ")
                append(event.timestamp)
                append(" ")
                append(event.level.name)
                append(" ")
                append(event.category.name)
                append(" `")
                append(event.id.toHandoffLine())
                append("`: ")
                append(event.message.toHandoffLine())
                if (includeDetails && !event.details.isNullOrBlank()) {
                    append(" details=")
                    append(event.details.toHandoffLine().take(EVENT_LOG_DETAILS_PAYLOAD_MAX_CHARS))
                }
                appendLine()
            }
        }
    }
}

private fun List<EventLogEntry>.toEventHandoffMarkdown(
    totalEventCount: Int,
    matchedEventCount: Int,
    category: EventCategory?,
    level: EventLevel?,
    limit: Int,
): String {
    val includedEvents = this
    return buildString {
        appendLine("# Events handoff")
        appendLine()
        appendLine("- Total event logs: $totalEventCount")
        appendLine("- Matching events: $matchedEventCount")
        appendLine("- Events included: ${includedEvents.size} of up to $limit")
        appendLine("- Category filter: ${category?.name ?: "Any"}")
        appendLine("- Level filter: ${level?.name ?: "Any"}")
        appendLine("- Recent first: true")
        appendLine("- Event details included: false")
        appendLine()
        appendLine("## Events")
        if (includedEvents.isEmpty()) {
            appendLine("_No recent events included._")
        } else {
            includedEvents.forEach { event ->
                appendLine(event.toEventHandoffMarkdownLine())
            }
        }
    }
}

private fun EventLogEntry.toEventHandoffMarkdownLine(): String =
    buildString {
        append("- ")
        append(timestamp)
        append(" ")
        append(level.name)
        append(" ")
        append(category.name)
        append(" `")
        append(id.toHandoffLine())
        append("`: ")
        append(message.toHandoffLine())
    }

private fun List<EventLogEntry>.toEventDoctorIssues(): List<EventDoctorIssue> =
    filter { event -> event.level == EventLevel.Error || event.level == EventLevel.Warn }
        .map { event ->
            EventDoctorIssue(
                id = "${event.id}:${event.level.toEventDoctorCode()}",
                severity = event.level.toEventDoctorSeverity(),
                code = event.level.toEventDoctorCode(),
                eventId = event.id,
                category = event.category.name,
                level = event.level.name,
                timestamp = event.timestamp,
                summary =
                    "Recent ${event.category.name} ${event.level.name} event: ${event.message}"
                        .toEventDoctorText(),
                action = event.category.toEventDoctorAction(event.level).toEventDoctorText(),
            )
        }

private fun EventLevel.toEventDoctorSeverity(): String =
    when (this) {
        EventLevel.Error -> "Error"
        EventLevel.Warn -> "Warning"
        EventLevel.Info -> "Info"
    }

private fun EventLevel.toEventDoctorCode(): String =
    when (this) {
        EventLevel.Error -> "event.error.recent"
        EventLevel.Warn -> "event.warning.recent"
        EventLevel.Info -> "event.info.recent"
    }

private fun EventCategory.toEventDoctorAction(level: EventLevel): String {
    val prefix =
        when (level) {
            EventLevel.Error -> "Inspect and fix the failing"
            EventLevel.Warn -> "Review the warning from the"
            EventLevel.Info -> "Review the"
        }
    return when (this) {
        EventCategory.Provider -> "$prefix provider path; run providers.doctor and verify auth, endpoint, model, and network state."
        EventCategory.Tool -> "$prefix tool path; run tools.doctor and inspect the referenced tool arguments or permissions."
        EventCategory.Scheduler -> "$prefix scheduler path; run tasks.doctor and inspect due/retry/precision state."
        EventCategory.Skill -> "$prefix skill path; run skills.doctor and inspect skill metadata, eligibility, and configuration."
        EventCategory.System -> "$prefix system path; run runtime.doctor for cross-contract readiness."
        EventCategory.Debug -> "$prefix debug path; inspect the emitting component or reduce debug logging if expected."
    }
}

private fun EventLogEntry.toEventDoctorCheckPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("timestampIso", timestamp.toString())
        put("category", category.name)
        put("level", level.name)
        put("message", message.take(EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS))
        put("messageTruncated", message.length > EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS)
        put("hasDetails", details != null)
        put("detailsIncluded", false)
    }

private fun List<EventDoctorIssue>.toEventDoctorStatus(): String =
    when {
        any { issue -> issue.severity == "Error" } -> "ERROR"
        any { issue -> issue.severity == "Warning" } -> "WARN"
        else -> "OK"
    }

private fun EventDoctorIssue.toEventDoctorPayload(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("severity", severity)
        put("code", code)
        put("eventId", eventId)
        put("category", category)
        put("level", level)
        put("timestampIso", timestamp.toString())
        put("summary", summary)
        put("action", action)
    }

private fun List<EventDoctorIssue>.toEventDoctorMarkdown(
    status: String,
    totalEventCount: Int,
    scannedEventCount: Int,
    matchedEventCount: Int,
    issueCount: Int,
    category: EventCategory?,
    limit: Int,
): String {
    val includedIssues = this
    return buildString {
        appendLine("# Events doctor")
        appendLine()
        appendLine("- Status: $status")
        appendLine("- Total event logs: $totalEventCount")
        appendLine("- Events scanned: $scannedEventCount")
        appendLine("- Matching events: $matchedEventCount")
        appendLine("- Category filter: ${category?.name ?: "Any"}")
        appendLine("- Issues included: ${includedIssues.size} of $issueCount")
        appendLine("- Limit: $limit")
        appendLine("- Event details included: false")
        appendLine()
        appendLine("## Issues")
        if (includedIssues.isEmpty()) {
            appendLine("_No warning or error events found._")
        } else {
            includedIssues.forEach { issue ->
                appendLine(issue.toEventDoctorMarkdownLine())
            }
        }
    }
}

private fun EventDoctorIssue.toEventDoctorMarkdownLine(): String =
    buildString {
        append("- ")
        append(severity)
        append(" `")
        append(eventId.toHandoffLine())
        append("` category=")
        append(category)
        append(" level=")
        append(level)
        append(" code=")
        append(code)
        append(" at=")
        append(timestamp)
        append(": ")
        append(summary.toHandoffLine())
        append(" Action: ")
        append(action.toHandoffLine())
    }

private fun String.toEventDoctorText(): String = toHandoffLine().take(EVENT_DOCTOR_TEXT_MAX_CHARS)


private data class EventDoctorIssue(
    val id: String,
    val severity: String,
    val code: String,
    val eventId: String,
    val category: String,
    val level: String,
    val timestamp: Instant,
    val summary: String,
    val action: String,
)

private data class EventImportCandidate(
    val sourceIndex: Int,
    val sourceEventId: String?,
    val sourceTimestampIso: String?,
    val category: EventCategory,
    val level: EventLevel,
    val message: String,
    val details: String?,
)

private data class EventImportedItem(
    val candidate: EventImportCandidate,
    val event: EventLogEntry,
    val detailsImported: Boolean,
)

private data class EventImportSkippedEntry(
    val sourceIndex: Int,
    val code: String,
    val summary: String,
)

private sealed interface EventImportEntriesParseResult {
    data class Success(
        val entries: JsonArray,
    ) : EventImportEntriesParseResult

    data class Failure(
        val result: ToolExecutionResult,
    ) : EventImportEntriesParseResult
}

private sealed interface EventImportCandidateParseResult {
    data class Candidate(
        val candidate: EventImportCandidate,
    ) : EventImportCandidateParseResult

    data class Skipped(
        val skipped: EventImportSkippedEntry,
    ) : EventImportCandidateParseResult
}


internal const val EVENT_DOCTOR_DEFAULT_LIMIT = 20
internal const val EVENT_DOCTOR_MAX_LIMIT = 50
internal const val EVENT_DOCTOR_TEXT_MAX_CHARS = 500
internal const val EVENT_EXPORT_FORMAT = "androidclaw.events.export.v1"
internal const val EVENT_EXPORT_VERSION = 1
internal const val EVENT_EXPORT_DEFAULT_LIMIT = 50
internal const val EVENT_EXPORT_MAX_LIMIT = 100
internal const val EVENT_HANDOFF_DEFAULT_LIMIT = 12
internal const val EVENT_HANDOFF_MAX_LIMIT = 50
internal const val EVENT_IMPORT_FORMAT = "androidclaw.events.import.v1"
internal const val EVENT_IMPORT_VERSION = 1
internal const val EVENT_IMPORT_DEFAULT_LIMIT = 50
internal const val EVENT_IMPORT_MAX_LIMIT = 100
internal const val EVENT_LOG_DEFAULT_LIMIT = 20
internal const val EVENT_LOG_MAX_LIMIT = 50
internal const val EVENT_LOG_SCAN_LIMIT = 200
internal const val EVENT_LOG_STATS_MAX_SCAN_LIMIT = 500
internal const val EVENT_LOG_MESSAGE_PAYLOAD_MAX_CHARS = 500
internal const val EVENT_LOG_DETAILS_PAYLOAD_MAX_CHARS = 1_000
internal const val EVENT_LOG_FILTER_MAX_CHARS = 80
