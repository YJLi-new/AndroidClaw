package ai.androidclaw.ui.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

/** In-app language choice. [System] follows the device language. */
enum class AppLanguage(
    val languageTag: String?,
) {
    System(null),
    English("en"),
    SimplifiedChinese("zh-CN"),
    ;

    companion object {
        fun fromLanguageTag(tag: String?): AppLanguage {
            val normalized = tag?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when {
                normalized.isEmpty() -> System
                normalized.startsWith("zh") -> SimplifiedChinese
                normalized.startsWith("en") -> English
                else -> System
            }
        }
    }
}

/**
 * Per-app language without AppCompat.
 *
 * API 33+ delegates to [LocaleManager] (the system persists the choice and
 * recreates the activity). API 26-32 stores the tag in a small preferences file
 * and [MainActivity][ai.androidclaw.app.MainActivity] applies it through
 * [overrideConfiguration] before resources are first read.
 */
object AppLocaleController {
    private const val PREFERENCES_NAME = "androidclaw_ui_locale"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentFromLocaleManager(context)
        } else {
            AppLanguage.fromLanguageTag(storedLanguageTag(context))
        }

    fun apply(
        activity: Activity,
        language: AppLanguage,
    ) {
        if (current(activity) == language) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applyWithLocaleManager(activity, language)
        } else {
            activity
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE_TAG, language.languageTag)
                .commit()
            activity.recreate()
        }
    }

    /**
     * Returns the configuration to apply on API 26-32, or null when the app
     * follows the system language (or the platform handles it natively).
     */
    fun overrideConfiguration(base: Context): Configuration? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return null
        }
        val tag = storedLanguageTag(base) ?: return null
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        return Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
    }

    private fun storedLanguageTag(context: Context): String? =
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, null)
            ?.takeIf(String::isNotBlank)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun currentFromLocaleManager(context: Context): AppLanguage {
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        if (locales == null || locales.isEmpty) {
            return AppLanguage.System
        }
        return AppLanguage.fromLanguageTag(locales[0].toLanguageTag())
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyWithLocaleManager(
        context: Context,
        language: AppLanguage,
    ) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales =
            language.languageTag
                ?.let(LocaleList::forLanguageTags)
                ?: LocaleList.getEmptyLocaleList()
    }
}
