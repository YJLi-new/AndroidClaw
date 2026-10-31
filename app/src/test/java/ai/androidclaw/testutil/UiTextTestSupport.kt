package ai.androidclaw.testutil

import ai.androidclaw.ui.text.UiText
import ai.androidclaw.ui.text.resolve
import android.content.Context
import androidx.test.core.app.ApplicationProvider

/** Resolves [UiText] with the Robolectric application context (default English resources). */
fun UiText.text(): String = resolve(ApplicationProvider.getApplicationContext<Context>())

/** Null-safe variant of [text] for optional notice and error fields. */
fun UiText?.textOrNull(): String? = this?.text()
