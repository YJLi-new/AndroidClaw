package ai.androidclaw.app

import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
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
}
