package ai.androidclaw.feature.settings

import ai.androidclaw.app.SettingsDependencies
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.runtime.providers.ProviderRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SettingsUiState(
    val activeProviderId: String = "",
    val providerType: String = "fake",
    val configured: Boolean = false,
    val buildPosture: String = "",
)

class SettingsViewModel(
    providerRegistry: ProviderRegistry,
    settingsDataStore: SettingsDataStore,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = settingsDataStore.settings
        .map { settings ->
            SettingsUiState(
                activeProviderId = providerRegistry.defaultProvider.id,
                providerType = settings.providerType,
                configured = settings.providerType == "fake",
                buildPosture = "Single-app-module, manual DI, Compose navigation shell, FakeProvider-first.",
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SettingsUiState(
                activeProviderId = providerRegistry.defaultProvider.id,
                buildPosture = "Single-app-module, manual DI, Compose navigation shell, FakeProvider-first.",
            ),
        )

    companion object {
        fun factory(dependencies: SettingsDependencies): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(
                        providerRegistry = dependencies.providerRegistry,
                        settingsDataStore = dependencies.settingsDataStore,
                    ) as T
                }
            }
        }
    }
}
