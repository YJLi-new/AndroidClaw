package ai.androidclaw.runtime.tools

import ai.androidclaw.runtime.providers.NetworkStatusProvider
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

private const val CLIPBOARD_READ_MAX_CHARS = 4_000
private const val CLIPBOARD_WRITE_MAX_CHARS = 10_000
private const val TIMER_MAX_SECONDS = 24 * 60 * 60
private val OPEN_URL_SCHEMES = setOf("http", "https", "mailto", "tel", "geo", "sms", "smsto")

internal val SETTINGS_PANELS: Map<String, String> =
    linkedMapOf(
        "settings" to Settings.ACTION_SETTINGS,
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "mobile_data" to Settings.ACTION_DATA_ROAMING_SETTINGS,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "date_time" to Settings.ACTION_DATE_SETTINGS,
        "language" to Settings.ACTION_LOCALE_SETTINGS,
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "security" to Settings.ACTION_SECURITY_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "app_details" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "app_notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
    )

private val WEEKDAYS =
    mapOf(
        "sun" to Calendar.SUNDAY,
        "mon" to Calendar.MONDAY,
        "tue" to Calendar.TUESDAY,
        "wed" to Calendar.WEDNESDAY,
        "thu" to Calendar.THURSDAY,
        "fri" to Calendar.FRIDAY,
        "sat" to Calendar.SATURDAY,
    )

internal fun deviceToolEntries(
    application: Application,
    networkStatusProvider: NetworkStatusProvider?,
    isAppInForeground: () -> Boolean,
    clock: Clock,
): List<ToolRegistry.Entry> {
    fun foreground(name: String): () -> ToolAvailability =
        {
            if (isAppInForeground()) {
                ToolAvailability()
            } else {
                ToolAvailability(
                    status = ToolAvailabilityStatus.ForegroundRequired,
                    reason = "$name needs AndroidClaw open on screen.",
                )
            }
        }

    fun launch(
        intent: Intent,
        whatFor: String,
    ): ToolExecutionResult? =
        try {
            application.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (_: ActivityNotFoundException) {
            toolFailure("NO_HANDLER_APP", "No app on this phone can $whatFor.")
        } catch (error: SecurityException) {
            toolFailure("LAUNCH_DENIED", "Android blocked the request to $whatFor: ${error.message}")
        }

    return listOf(
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "device_status",
                    aliases = listOf("device.status"),
                    description = "Current local time, timezone, locale, battery, network and Android version.",
                    riskTier = ToolRiskTier.ReadOnly,
                    allowedOrigins = INTERACTIVE_AND_SCHEDULED_ORIGINS,
                ),
        ) { _, _ ->
            val now = ZonedDateTime.now(clock)
            val battery = application.batterySnapshot()
            val power = application.getSystemService(PowerManager::class.java)
            val network = networkStatusProvider?.currentStatus()
            val locale = Locale.getDefault().toLanguageTag()
            toolSuccess(
                summary =
                    buildString {
                        append("Local time ").append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm, EEEE", Locale.ENGLISH)))
                        append(" (")
                            .append(now.zone.id)
                            .append(", UTC")
                            .append(now.offset.id.replace("Z", "+00:00"))
                            .append(").")
                        append("\nLocale ").append(locale).append(". Android ").append(Build.VERSION.RELEASE)
                        append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
                        append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('.')
                        append("\nBattery ").append(battery)
                        if (power?.isPowerSaveMode == true) append(", battery saver on")
                        append(". Network: ").append(network?.summary ?: "unknown").append('.')
                    },
                payload =
                    buildJsonObject {
                        put("localTime", now.toLocalDateTime().toString())
                        put("timezone", now.zone.id)
                        put("utcOffset", now.offset.id)
                        put("locale", locale)
                        put("sdkInt", Build.VERSION.SDK_INT)
                    },
            )
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "open_url",
                    description = "Open a link in the user's browser or matching app (http, https, mailto, tel, geo, sms).",
                    riskTier = ToolRiskTier.ExternalSideEffect,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    foregroundRequired = true,
                    arguments = listOf(stringArg("url", "The link to open.", required = true)),
                ),
            availabilityProvider = foreground("open_url"),
        ) { _, arguments ->
            val raw = arguments.requireText("url", "open_url")
            val uri = Uri.parse(raw)
            val scheme = uri.scheme?.lowercase()
            if (scheme !in OPEN_URL_SCHEMES) {
                return@Entry toolFailure("INVALID_URL", "open_url supports ${OPEN_URL_SCHEMES.joinToString()} links.")
            }
            val action = if (scheme == "tel") Intent.ACTION_DIAL else Intent.ACTION_VIEW
            launch(Intent(action, uri), "open $raw") ?: toolSuccess("Opened $raw.")
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "open_app",
                    description = "Launch an installed app by name or package (e.g. 'Camera' or 'com.android.chrome').",
                    riskTier = ToolRiskTier.ExternalSideEffect,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    foregroundRequired = true,
                    arguments = listOf(stringArg("app", "App label or package name.", required = true)),
                ),
            availabilityProvider = foreground("open_app"),
        ) { _, arguments ->
            val query = arguments.requireText("app", "open_app")
            val match =
                application.findLaunchableApp(query)
                    ?: return@Entry toolFailure("APP_NOT_FOUND", "No launchable app matches \"$query\".")
            val intent =
                application.packageManager.getLaunchIntentForPackage(match.first)
                    ?: return@Entry toolFailure("APP_NOT_FOUND", "${match.second} has no launcher screen.")
            launch(intent, "open ${match.second}") ?: toolSuccess("Opened ${match.second}.", buildJsonObject { put("package", match.first) })
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "open_settings",
                    description = "Open a system settings screen.",
                    riskTier = ToolRiskTier.ExternalSideEffect,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    foregroundRequired = true,
                    arguments =
                        listOf(
                            stringArg(
                                "panel",
                                "Which screen. app_* panels open AndroidClaw's own page.",
                                required = true,
                                enumValues = SETTINGS_PANELS.keys.toList(),
                            ),
                        ),
                ),
            availabilityProvider = foreground("open_settings"),
        ) { _, arguments ->
            val panel = arguments.requireText("panel", "open_settings").lowercase()
            val action =
                SETTINGS_PANELS[panel]
                    ?: return@Entry toolFailure("INVALID_PANEL", "Unknown panel $panel. Use one of ${SETTINGS_PANELS.keys.joinToString()}.")
            val intent =
                Intent(action).apply {
                    when (panel) {
                        "app_details" -> data = Uri.fromParts("package", application.packageName, null)
                        "app_notifications" -> putExtra(Settings.EXTRA_APP_PACKAGE, application.packageName)
                    }
                }
            if (launch(intent, "show $panel settings") == null) {
                toolSuccess("Opened $panel settings.")
            } else {
                launch(Intent(Settings.ACTION_SETTINGS), "show settings")
                    ?: toolSuccess("This phone has no $panel screen; opened the main settings instead.")
            }
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "set_alarm",
                    description = "Create an alarm in the clock app. The clock app may ask the user to confirm.",
                    riskTier = ToolRiskTier.ExternalSideEffect,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    foregroundRequired = true,
                    arguments =
                        listOf(
                            intArg("hour", "0-23.", required = true),
                            intArg("minute", "0-59.", required = true),
                            stringArg("label", "Alarm label."),
                            stringArg("days", "Repeat days, comma separated: mon,tue,wed,thu,fri,sat,sun."),
                        ),
                ),
            availabilityProvider = foreground("set_alarm"),
        ) { _, arguments ->
            val hour = arguments.optionalLongOrNull("hour")?.toInt()
            val minute = arguments.optionalLongOrNull("minute")?.toInt()
            require(hour != null && hour in 0..23) { "set_alarm requires hour 0-23." }
            require(minute != null && minute in 0..59) { "set_alarm requires minute 0-59." }
            val days = parseWeekdays(arguments.optionalText("days"))
            val label = arguments.optionalText("label")
            val intent =
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                }
            val time = "%02d:%02d".format(Locale.ROOT, hour, minute)
            launch(intent, "set alarms") ?: toolSuccess("Asked the clock app to set an alarm for $time${label?.let { " ($it)" }.orEmpty()}.")
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "set_timer",
                    description = "Start a countdown timer in the clock app.",
                    riskTier = ToolRiskTier.ExternalSideEffect,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    foregroundRequired = true,
                    arguments =
                        listOf(
                            intArg("seconds", "Length in seconds, 1-86400.", required = true),
                            stringArg("label", "Timer label."),
                        ),
                ),
            availabilityProvider = foreground("set_timer"),
        ) { _, arguments ->
            val seconds = arguments.optionalLongOrNull("seconds")
            require(seconds != null && seconds in 1..TIMER_MAX_SECONDS) { "set_timer requires seconds between 1 and $TIMER_MAX_SECONDS." }
            val label = arguments.optionalText("label")
            val intent =
                Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
                    label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                }
            launch(intent, "set timers") ?: toolSuccess("Started a ${Duration.ofSeconds(seconds).toReadable()} timer${label?.let { " ($it)" }.orEmpty()}.")
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "calendar_add_event",
                    description = "Open the calendar app with a prefilled new event; the user saves it there.",
                    riskTier = ToolRiskTier.ExternalSideEffect,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    foregroundRequired = true,
                    arguments =
                        listOf(
                            stringArg("title", "Event title.", required = true),
                            stringArg("startIso", "Start, local or ISO date-time.", required = true),
                            stringArg("endIso", "End. Default start + 1 hour."),
                            stringArg("location", "Place."),
                            stringArg("description", "Notes."),
                            boolArg("allDay", "All-day event."),
                        ),
                ),
            availabilityProvider = foreground("calendar_add_event"),
        ) { _, arguments ->
            val title = arguments.requireText("title", "calendar_add_event")
            val start = parseInstantArgument(arguments.requireText("startIso", "calendar_add_event"), clock.zone, "calendar_add_event", "startIso")
            val end =
                arguments.optionalText("endIso")?.let { parseInstantArgument(it, clock.zone, "calendar_add_event", "endIso") }
                    ?: start.plus(Duration.ofHours(1))
            require(!end.isBefore(start)) { "calendar_add_event: endIso must not be before startIso." }
            val intent =
                Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
                    putExtra(CalendarContract.Events.TITLE, title)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toEpochMilli())
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toEpochMilli())
                    putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, arguments.optionalBoolean("allDay"))
                    arguments.optionalText("location")?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                    arguments.optionalText("description")?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
                }
            launch(intent, "add calendar events")
                ?: toolSuccess("Opened the calendar with \"$title\" at ${start.atZone(clock.zone).toLocalDateTime()}; the user confirms it there.")
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "clipboard_get",
                    description = "Read the text currently on the clipboard.",
                    riskTier = ToolRiskTier.ReadOnly,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    foregroundRequired = true,
                ),
            availabilityProvider = foreground("clipboard_get"),
        ) { _, _ ->
            val clipboard = application.getSystemService(ClipboardManager::class.java)
            val text =
                clipboard
                    ?.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(application)
                    ?.toString()
            if (text.isNullOrEmpty()) {
                toolSuccess("The clipboard is empty or holds no text.")
            } else {
                toolSuccess(
                    summary = "Clipboard text (${text.length} chars).",
                    payload = buildJsonObject { put("text", text.clip(CLIPBOARD_READ_MAX_CHARS)) },
                )
            }
        },
        ToolRegistry.Entry(
            descriptor =
                ToolDescriptor(
                    name = "clipboard_set",
                    description = "Copy text to the clipboard.",
                    riskTier = ToolRiskTier.WritesLocalState,
                    allowedOrigins = INTERACTIVE_ORIGINS,
                    foregroundRequired = true,
                    arguments = listOf(stringArg("text", "Text to copy.", required = true)),
                ),
            availabilityProvider = foreground("clipboard_set"),
        ) { _, arguments ->
            val text = arguments.optionalRawText("text") ?: throw IllegalArgumentException("clipboard_set requires text.")
            require(text.length <= CLIPBOARD_WRITE_MAX_CHARS) { "clipboard_set accepts at most $CLIPBOARD_WRITE_MAX_CHARS characters." }
            val clipboard =
                application.getSystemService(ClipboardManager::class.java)
                    ?: return@Entry toolFailure("TOOL_UNAVAILABLE", "The clipboard is not available.")
            clipboard.setPrimaryClip(ClipData.newPlainText("AndroidClaw", text))
            toolSuccess("Copied ${text.length} characters to the clipboard.")
        },
    )
}

private fun Application.batterySnapshot(): String {
    val intent =
        ContextCompat.registerReceiver(
            this,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        ) ?: return "unknown"
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val percent = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "level unknown"
    val state =
        when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "state unknown"
        }
    return "$percent, $state"
}

/** Returns (packageName, label) of the best launchable match for [query]. */
private fun Application.findLaunchableApp(query: String): Pair<String, String>? {
    val normalized = query.trim().lowercase()
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }
    val apps =
        activities
            .map { info -> info.activityInfo.packageName to info.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
    return apps.firstOrNull { it.first.lowercase() == normalized }
        ?: apps.firstOrNull { it.second.lowercase() == normalized }
        ?: apps.firstOrNull { it.second.lowercase().startsWith(normalized) }
        ?: apps.firstOrNull { it.second.lowercase().contains(normalized) }
        ?: packageManager.getLaunchIntentForPackage(query.trim())?.let { query.trim() to query.trim() }
}

private fun parseWeekdays(raw: String?): List<Int> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw
        .split(',', ' ', ';')
        .map { it.trim().lowercase().take(3) }
        .filter(String::isNotEmpty)
        .map { day -> WEEKDAYS[day] ?: throw IllegalArgumentException("set_alarm: unknown day $day; use mon,tue,wed,thu,fri,sat,sun.") }
        .distinct()
}

private fun Duration.toReadable(): String {
    val total = seconds
    val hours = total / 3_600
    val minutes = (total % 3_600) / 60
    val seconds = total % 60
    return buildList {
        if (hours > 0) add("${hours}h")
        if (minutes > 0) add("${minutes}m")
        if (seconds > 0) add("${seconds}s")
    }.joinToString(" ").ifEmpty { "0s" }
}
