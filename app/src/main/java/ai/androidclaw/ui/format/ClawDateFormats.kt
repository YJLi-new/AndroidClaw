package ai.androidclaw.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Locale-aware date-time formatter in the device time zone. */
@Composable
fun rememberDateTimeFormatter(dateStyle: FormatStyle = FormatStyle.MEDIUM): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, dateStyle) {
        DateTimeFormatter
            .ofLocalizedDateTime(dateStyle, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
    }
}

@Composable
fun rememberDateFormatter(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
}

@Composable
fun rememberTimeFormatter(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        DateTimeFormatter
            .ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
    }
}
