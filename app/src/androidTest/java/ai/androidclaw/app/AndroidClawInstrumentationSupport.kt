package ai.androidclaw.app

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal fun resetApplicationState(
    application: AndroidClawApplication,
    onboardingCompleted: Boolean,
    providerSettings: ProviderSettingsSnapshot = ProviderSettingsSnapshot(),
) = runBlocking {
    withContext(Dispatchers.IO) {
        application.container.database.clearAllTables()
    }
    application.container.settingsDataStore.saveProviderSettings(providerSettings)
    application.container.onboardingDataStore.setCompleted(onboardingCompleted)
    ProviderType.configurableProviders.forEach { providerType ->
        application.container.providerSecretStore.writeApiKey(providerType, null)
    }
    application.container.ensureMainSession()
    grantNotificationPermission(application)
}

/** Creating a task asks for POST_NOTIFICATIONS on API 33+; grant it so no system dialog covers the UI. */
private fun grantNotificationPermission(application: AndroidClawApplication) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(application.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
}
