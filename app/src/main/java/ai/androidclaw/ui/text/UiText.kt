package ai.androidclaw.ui.text

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources

/**
 * User-visible text produced outside composition (ViewModels, formatters).
 *
 * String resources are resolved at render time so a language switch re-renders
 * without touching ViewModel state. [Raw] carries text that originates from the
 * runtime, providers, or the user and therefore cannot be translated here.
 */
sealed interface UiText {
    data class Raw(
        val value: String,
    ) : UiText

    data class Res(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Plural(
        @param:PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = listOf(count),
    ) : UiText

    companion object {
        fun raw(value: String): UiText = Raw(value)

        fun res(
            @StringRes id: Int,
            vararg args: Any,
        ): UiText = Res(id, args.toList())

        fun plural(
            @PluralsRes id: Int,
            count: Int,
            vararg args: Any,
        ): UiText = Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
    }
}

fun UiText.resolve(context: Context): String = resolve(context.resources)

fun UiText.resolve(resources: Resources): String =
    when (this) {
        is UiText.Raw -> value
        is UiText.Res ->
            if (args.isEmpty()) {
                resources.getString(id)
            } else {
                resources.getString(id, *args.resolveArgs(resources))
            }
        is UiText.Plural -> resources.getQuantityString(id, count, *args.resolveArgs(resources))
    }

/** Resolves this text for the current composition locale. */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String {
    // Reading the configuration subscribes this call site to locale changes.
    LocalConfiguration.current
    return resolve(LocalResources.current)
}

private fun List<Any>.resolveArgs(resources: Resources): Array<Any> = map { arg -> if (arg is UiText) arg.resolve(resources) else arg }.toTypedArray()

/** Bounded raw text for messages that come from runtime or provider code. */
fun String?.toRawUiTextOrNull(maxChars: Int): UiText? =
    this
        ?.take(maxChars)
        ?.takeIf(String::isNotBlank)
        ?.let(UiText::Raw)
