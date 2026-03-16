package ai.androidclaw.feature.settings

import ai.androidclaw.app.SettingsDependencies
import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.runtime.providers.ProviderRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val activeProviderId: String = "",
    val availableProviders: List<ProviderType> = emptyList(),
    val providerType: ProviderType = ProviderType.Fake,
    val baseUrl: String = "",
    val modelId: String = "",
    val timeoutSeconds: String = "",
    val apiKeyDraft: String = "",
    val hasStoredApiKey: Boolean = false,
    val configured: Boolean = false,
    val statusMessage: String? = null,
    val buildPosture: String = "",
)

class SettingsViewModel(
    private val providerRegistry: ProviderRegistry,
    private val settingsDataStore: SettingsDataStore,
    private val providerSecretStore: ProviderSecretStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SettingsUiState(
            availableProviders = providerRegistry.descriptors().map { it.type },
            buildPosture = "Single-app-module, manual DI, Compose navigation shell, FakeProvider plus OpenAI-compatible presets and native Claude.",
        ),
    )
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun selectProviderType(providerType: ProviderType) {
        viewModelScope.launch {
            val settings = settingsDataStore.settings.first()
            val storedApiKey = providerSecretStore.readApiKey(providerType)
            val endpointSettings = settings.endpointSettings(providerType)
            mutableState.update {
                it.copy(
                    providerType = providerType,
                    activeProviderId = providerRegistry.require(providerType).id,
                    baseUrl = endpointSettings.baseUrl,
                    modelId = endpointSettings.modelId,
                    timeoutSeconds = endpointSettings.timeoutSeconds.toString(),
                    apiKeyDraft = "",
                    hasStoredApiKey = !storedApiKey.isNullOrBlank(),
                    configured = isConfigured(
                        providerType = providerType,
                        baseUrl = endpointSettings.baseUrl,
                        modelId = endpointSettings.modelId,
                        apiKeyDraft = "",
                        hasStoredApiKey = !storedApiKey.isNullOrBlank(),
                    ),
                    statusMessage = null,
                )
            }
        }
    }

    fun onBaseUrlChanged(value: String) {
        mutableState.update {
            it.copy(
                baseUrl = value,
                configured = isConfigured(
                    providerType = it.providerType,
                    baseUrl = value,
                    modelId = it.modelId,
                    apiKeyDraft = it.apiKeyDraft,
                    hasStoredApiKey = it.hasStoredApiKey,
                ),
                statusMessage = null,
            )
        }
    }

    fun onModelIdChanged(value: String) {
        mutableState.update {
            it.copy(
                modelId = value,
                configured = isConfigured(
                    providerType = it.providerType,
                    baseUrl = it.baseUrl,
                    modelId = value,
                    apiKeyDraft = it.apiKeyDraft,
                    hasStoredApiKey = it.hasStoredApiKey,
                ),
                statusMessage = null,
            )
        }
    }

    fun onTimeoutChanged(value: String) {
        mutableState.update {
            it.copy(
                timeoutSeconds = value,
                statusMessage = null,
            )
        }
    }

    fun onApiKeyChanged(value: String) {
        mutableState.update {
            it.copy(
                apiKeyDraft = value,
                configured = isConfigured(
                    providerType = it.providerType,
                    baseUrl = it.baseUrl,
                    modelId = it.modelId,
                    apiKeyDraft = value,
                    hasStoredApiKey = it.hasStoredApiKey,
                ),
                statusMessage = null,
            )
        }
    }

    fun clearStoredApiKey() {
        val providerType = state.value.providerType
        if (!providerType.requiresRemoteSettings) {
            return
        }
        viewModelScope.launch {
            providerSecretStore.writeApiKey(providerType, null)
            mutableState.update {
                it.copy(
                    apiKeyDraft = "",
                    hasStoredApiKey = false,
                    configured = isConfigured(
                        providerType = it.providerType,
                        baseUrl = it.baseUrl,
                        modelId = it.modelId,
                        apiKeyDraft = "",
                        hasStoredApiKey = false,
                    ),
                    statusMessage = "Stored API key cleared.",
                )
            }
        }
    }

    fun save() {
        val snapshot = state.value
        val timeoutSeconds = snapshot.timeoutSeconds.toIntOrNull()
        if (snapshot.providerType.requiresRemoteSettings && (timeoutSeconds == null || timeoutSeconds <= 0)) {
            mutableState.update { it.copy(statusMessage = "Timeout must be a positive integer.") }
            return
        }
        val normalizedTimeoutSeconds = timeoutSeconds?.takeIf { it > 0 }
            ?: snapshot.providerType.defaultTimeoutSeconds

        viewModelScope.launch {
            val currentSettings = settingsDataStore.settings.first()
            val updatedSettings = if (snapshot.providerType.requiresRemoteSettings) {
                currentSettings.withEndpointSettings(
                    snapshot.providerType,
                    ProviderEndpointSettings(
                        baseUrl = snapshot.baseUrl,
                        modelId = snapshot.modelId,
                        timeoutSeconds = normalizedTimeoutSeconds,
                    ),
                ).copy(providerType = snapshot.providerType)
            } else {
                currentSettings.copy(providerType = snapshot.providerType)
            }
            settingsDataStore.saveProviderSettings(updatedSettings)
            if (snapshot.providerType.requiresRemoteSettings && snapshot.apiKeyDraft.isNotBlank()) {
                providerSecretStore.writeApiKey(
                    providerType = snapshot.providerType,
                    apiKey = snapshot.apiKeyDraft,
                )
            }
            refresh(statusMessage = "Provider settings saved.")
        }
    }

    private fun refresh(statusMessage: String? = null) {
        viewModelScope.launch {
            val settings = settingsDataStore.settings.first()
            val providerType = settings.providerType
            val endpointSettings = settings.endpointSettings(providerType)
            val storedApiKey = providerSecretStore.readApiKey(providerType)
            mutableState.value = SettingsUiState(
                activeProviderId = providerRegistry.require(providerType).id,
                availableProviders = providerRegistry.descriptors().map { it.type },
                providerType = providerType,
                baseUrl = endpointSettings.baseUrl,
                modelId = endpointSettings.modelId,
                timeoutSeconds = endpointSettings.timeoutSeconds.toString(),
                apiKeyDraft = "",
                hasStoredApiKey = !storedApiKey.isNullOrBlank(),
                configured = isConfigured(
                    providerType = providerType,
                    baseUrl = endpointSettings.baseUrl,
                    modelId = endpointSettings.modelId,
                    apiKeyDraft = "",
                    hasStoredApiKey = !storedApiKey.isNullOrBlank(),
                ),
                statusMessage = statusMessage,
                buildPosture = "Single-app-module, manual DI, Compose navigation shell, FakeProvider plus OpenAI-compatible presets and native Claude.",
            )
        }
    }

    private fun isConfigured(
        providerType: ProviderType,
        baseUrl: String,
        modelId: String,
        apiKeyDraft: String,
        hasStoredApiKey: Boolean,
    ): Boolean {
        return when {
            !providerType.requiresRemoteSettings -> true
            else -> {
                baseUrl.isNotBlank() &&
                    modelId.isNotBlank() &&
                    (apiKeyDraft.isNotBlank() || hasStoredApiKey)
            }
        }
    }

    companion object {
        fun factory(dependencies: SettingsDependencies): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(
                        providerRegistry = dependencies.providerRegistry,
                        settingsDataStore = dependencies.settingsDataStore,
                        providerSecretStore = dependencies.providerSecretStore,
                    ) as T
                }
            }
        }
    }
}
