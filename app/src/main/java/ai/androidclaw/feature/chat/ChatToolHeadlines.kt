package ai.androidclaw.feature.chat

import ai.androidclaw.R
import ai.androidclaw.ui.text.UiText
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Tool summaries stay English because the model reads them. The tool card headline
 * (the first summary line) is UI text, so the success templates built in `runtime/tools`
 * are shown from string resources here. Data lines, the expanded result and failure
 * details are what the model saw and stay as is; a headline that matches no template
 * (or a template changed on the runtime side) is shown unchanged.
 */
internal fun localizedToolHeadline(
    toolName: String,
    headline: String,
    locale: Locale,
): UiText? {
    if (headline.length > TOOL_HEADLINE_MATCH_MAX_CHARS) return null
    val name = toolName.trim().replace('.', '_')
    if (name == "device_status") return deviceTimeHeadline(headline, locale)
    val rules = TOOL_HEADLINE_RULES[name] ?: return null
    return rules.firstNotNullOfOrNull { rule ->
        rule.pattern.matchEntire(headline)?.let { match -> rule.text(match.groupValues.drop(1)) }
    }
}

private const val TOOL_HEADLINE_MATCH_MAX_CHARS = 1_000

private val DEVICE_TIME = Regex("""Local time (\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}), [A-Za-z]+ \((.+), UTC([+-]\d{2}:\d{2})\)\.""")

/** The weekday is English in the summary; it is rebuilt from the date in the app language. */
private fun deviceTimeHeadline(
    headline: String,
    locale: Locale,
): UiText? {
    val (date, time, zone, offset) = DEVICE_TIME.matchEntire(headline)?.destructured ?: return null
    val weekday = runCatching { LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.FULL, locale) }.getOrNull() ?: return null
    return UiText.res(R.string.chat_tool_headline_device_time, "$date $time", weekday, zone, offset)
}

private class HeadlineRule(
    val pattern: Regex,
    val text: (List<String>) -> UiText,
)

/** Every capture group is passed as a string argument. */
private fun rule(
    pattern: String,
    @StringRes id: Int,
) = HeadlineRule(Regex(pattern)) { groups -> UiText.Res(id, groups) }

/** Group [countGroup] picks the plural form and is passed as an Int; the others as strings. */
private fun counted(
    pattern: String,
    @PluralsRes id: Int,
    countGroup: Int = 0,
) = HeadlineRule(Regex(pattern)) { groups ->
    val count = groups[countGroup].toInt()
    UiText.Plural(id, count, groups.mapIndexed { index, group -> if (index == countGroup) count else group })
}

private val MEMORY_LIST_RULES =
    listOf(
        rule("""No memories stored(?: yet)?\.""", R.string.chat_tool_headline_memory_none),
        rule("""No matching memories\.""", R.string.chat_tool_headline_memory_no_match),
        counted("""(\d{1,9}) memory item\(s\):""", R.plurals.chat_tool_headline_memory_count),
    )

private val MEMORY_DELETED_RULE = rule("""Deleted memory \S+\.""", R.string.chat_tool_headline_memory_deleted)

private val OPENED_RULE = rule("""Opened (.+)\.""", R.string.chat_tool_headline_opened)

private val TOOL_HEADLINE_RULES: Map<String, List<HeadlineRule>> =
    mapOf(
        "health_status" to listOf(rule("""Provider: (.+)\.""", R.string.chat_tool_headline_provider)),
        "open_url" to listOf(OPENED_RULE),
        "open_app" to listOf(OPENED_RULE),
        "open_settings" to
            listOf(
                rule("""Opened (\S+) settings\.""", R.string.chat_tool_headline_opened_settings),
                rule("""This phone has no (\S+) screen; opened the main settings instead\.""", R.string.chat_tool_headline_opened_main_settings),
            ),
        "set_alarm" to
            listOf(
                rule("""Asked the clock app to set an alarm for (\d{2}:\d{2}) \((.+)\)\.""", R.string.chat_tool_headline_alarm_labeled),
                rule("""Asked the clock app to set an alarm for (\d{2}:\d{2})\.""", R.string.chat_tool_headline_alarm),
            ),
        "set_timer" to
            listOf(
                rule("""Started a ([0-9hms ]+) timer \((.+)\)\.""", R.string.chat_tool_headline_timer_labeled),
                rule("""Started a ([0-9hms ]+) timer\.""", R.string.chat_tool_headline_timer),
            ),
        "calendar_add_event" to
            listOf(
                HeadlineRule(Regex("""Opened the calendar with "(.*)" at (\S+); the user confirms it there\.""")) { (title, start) ->
                    UiText.res(R.string.chat_tool_headline_calendar, title, start.replace('T', ' '))
                },
            ),
        "clipboard_get" to
            listOf(
                rule("""The clipboard is empty or holds no text\.""", R.string.chat_tool_headline_clipboard_empty),
                counted("""Clipboard text \((\d{1,9}) chars\)\.""", R.plurals.chat_tool_headline_clipboard_text),
            ),
        "clipboard_set" to listOf(counted("""Copied (\d{1,9}) characters to the clipboard\.""", R.plurals.chat_tool_headline_clipboard_copied)),
        "notify" to listOf(rule("""Posted notification "(.*)"\.""", R.string.chat_tool_headline_notification)),
        "memory_search" to MEMORY_LIST_RULES,
        "memory_remember" to listOf(rule("""Remembered \(id=[^)]*\): (.*)""", R.string.chat_tool_headline_memory_remembered)),
        "memory_forget" to listOf(MEMORY_DELETED_RULE),
        "memory_command" to
            MEMORY_LIST_RULES +
            listOf(
                counted("""Memory is on; (\d{1,9}) stored item\(s\)\. Commands: (.*)""", R.plurals.chat_tool_headline_memory_status_on),
                counted("""Memory is off; (\d{1,9}) stored item\(s\)\. Commands: (.*)""", R.plurals.chat_tool_headline_memory_status_off),
                rule("""Stored memory: (.*)""", R.string.chat_tool_headline_memory_remembered),
                MEMORY_DELETED_RULE,
                counted("""Cleared (\d{1,9}) memory item\(s\)\.""", R.plurals.settings_notice_memory_cleared),
            ),
        "sessions_list" to
            listOf(
                rule("""No sessions yet\.""", R.string.chat_tool_headline_sessions_none),
                counted("""(\d{1,9}) session\(s\):""", R.plurals.chat_tool_headline_sessions_count),
            ),
        "sessions_history" to
            listOf(counted("""(\d{1,9}) message\(s\) from "(.*)" \(id=[^)]*\)\.""", R.plurals.chat_tool_headline_session_history)),
        "sessions_create" to listOf(rule("""Created session "(.*)" \(id=[^)]*\)\.""", R.string.chat_tool_headline_session_created)),
        "sessions_rename" to listOf(rule("""Renamed session to "(.*)"\.""", R.string.chat_tool_headline_session_renamed)),
        "sessions_send" to listOf(rule("""Posted a note into "(.*)"\.""", R.string.chat_tool_headline_session_note)),
        "skills_list" to
            listOf(
                rule("""No skills installed\.""", R.string.chat_tool_headline_skills_none),
                counted("""(\d{1,9}) skill\(s\):""", R.plurals.chat_tool_headline_skills_count),
            ),
        "skills_set_enabled" to
            listOf(
                rule("""Enabled skill (.+)\.""", R.string.chat_tool_headline_skill_enabled),
                rule("""Disabled skill (.+)\.""", R.string.chat_tool_headline_skill_disabled),
            ),
        "tasks_list" to
            listOf(
                rule("""No scheduled tasks yet\..*""", R.string.chat_tool_headline_tasks_none),
                counted("""(\d{1,9}) task\(s\):""", R.plurals.chat_tool_headline_tasks_count),
            ),
        "tasks_create" to listOf(rule("""Created task:""", R.string.chat_tool_headline_task_created)),
        "tasks_update" to listOf(rule("""Updated task:""", R.string.chat_tool_headline_task_updated)),
        "tasks_delete" to listOf(rule("""Deleted task (.+)\.""", R.string.chat_tool_headline_task_deleted)),
        "tasks_run_now" to listOf(rule("""Queued (.+) to run now\. Check tasks_runs for the result\.""", R.string.chat_tool_headline_task_queued)),
        "tasks_runs" to
            listOf(
                rule("""No task runs recorded yet\.""", R.string.chat_tool_headline_task_runs_none),
                counted("""(\d{1,9}) run\(s\), newest first:""", R.plurals.chat_tool_headline_task_runs_count),
            ),
        "workspace_list" to listOf(counted("""(\d{1,9}) workspace file\(s\):""", R.plurals.chat_tool_headline_workspace_files)),
        "workspace_read" to
            listOf(
                rule(
                    """(.+): characters (\d{1,9})-(\d{1,9}) of (\d{1,9}); read again with offset=\d{1,9} for more\.""",
                    R.string.chat_tool_headline_workspace_read_part,
                ),
                counted("""(.+) \((\d{1,9}) characters\)\.""", R.plurals.chat_tool_headline_workspace_read, countGroup = 1),
            ),
        "workspace_write" to
            listOf(
                counted("""Wrote (.+) \((\d{1,9}) bytes\)\.""", R.plurals.chat_tool_headline_workspace_wrote, countGroup = 1),
                counted("""Appended to (.+) \((\d{1,9}) bytes\)\.""", R.plurals.chat_tool_headline_workspace_appended, countGroup = 1),
            ),
        "workspace_edit" to
            listOf(counted("""Edited (.+): replaced (\d{1,9}) occurrence\(s\)\.""", R.plurals.chat_tool_headline_workspace_edited, countGroup = 1)),
        "web_fetch" to
            listOf(
                rule("""Fetched (\S+) — (.+) \(\d{1,9} chars(?:, showing first \d{1,9})?\)\.""", R.string.chat_tool_headline_fetched_titled),
                rule("""Fetched (\S+) \(\d{1,9} chars(?:, showing first \d{1,9})?\)\.""", R.string.chat_tool_headline_fetched),
            ),
        "web_search" to
            listOf(
                rule("""No web results for "(.*)"\.""", R.string.chat_tool_headline_search_none),
                counted("""Found (\d{1,9}) web results for "(.*)"\.""", R.plurals.chat_tool_headline_search_count),
            ),
        // The attachment id is for the model; the image tile below the card shows the image.
        "image_generate" to listOf(rule("""Generated image \S+, (\d{1,5})×(\d{1,5})""", R.string.chat_tool_headline_image)),
        "video_generate" to listOf(rule("""Started video job \S+\. .*""", R.string.chat_tool_headline_video)),
    )
