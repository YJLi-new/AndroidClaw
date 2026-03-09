package ai.androidclaw.feature.settings

import ai.androidclaw.app.SettingsDependencies
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.data.OPENAI_DEFAULT_TIMEOUT_SECONDS
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
    val openAiBaseUrl: String = "",
    val openAiModelId: String = "",
    val openAiTimeoutSeconds: String = "",
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
            buildPosture = "Single-app-module, manual DI, Compose navigation shell, FakeProvider plus OpenAI-compatible provider.",
        ),
    )
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun selectProviderType(providerType: ProviderType) {
        mutableState.update {
            it.copy(
                providerType = providerType,
                activeProviderId = providerRegistry.require(providerType).id,
                configured = isConfigured(
                    providerType = providerType,
                    baseUrl = it.openAiBaseUrl,
                    modelId = it.openAiModelId,
                    apiKeyDraft = it.apiKeyDraft,
                    hasStoredApiKey = it.hasStoredApiKey,
                ),
                statusMessage = null,
            )
        }
    }

    fun onOpenAiBaseUrlChanged(value: String) {
        mutableState.update {
            it.copy(
                openAiBaseUrl = value,
                configured = isConfigured(
                    providerType = it.providerType,
                    baseUrl = value,
                    modelId = it.openAiModelId,
                    apiKeyDraft = it.apiKeyDraft,
                    hasStoredApiKey = it.hasStoredApiKey,
                ),
                statusMessage = null,
            )
        }
    }

    fun onOpenAiModelIdChanged(value: String) {
        mutableState.update {
            it.copy(
                openAiModelId = value,
                configured = isConfigured(
                    providerType = it.providerType,
                    baseUrl = it.openAiBaseUrl,
                    modelId = value,
                    apiKeyDraft = it.apiKeyDraft,
                    hasStoredApiKey = it.hasStoredApiKey,
                ),
                statusMessage = null,
            )
        }
    }

    fun onOpenAiTimeoutChanged(value: String) {
        mutableState.update {
            it.copy(
                openAiTimeoutSeconds = value,
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
                    baseUrl = it.openAiBaseUrl,
                    modelId = it.openAiModelId,
                    apiKeyDraft = value,
                    hasStoredApiKey = it.hasStoredApiKey,
                ),
                statusMessage = null,
            )
        }
    }

    fun clearStoredApiKey() {
        viewModelScope.launch {
            providerSecretStore.writeApiKey(ProviderType.OpenAiCompatible, null)
            mutableState.update {
                it.copy(
                    apiKeyDraft = "",
                    hasStoredApiKey = false,
                    configured = isConfigured(
                        providerType = it.providerType,
                        baseUrl = it.openAiBaseUrl,
                        modelId = it.openAiModelId,
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
        val timeoutSeconds = snapshot.openAiTimeoutSeconds.toIntOrNull()
        if (
            snapshot.providerType == ProviderType.OpenAiCompatible &&
            (timeoutSeconds == null || timeoutSeconds <= 0)
        ) {
            mutableState.update { it.copy(statusMessage = "Timeout must be a positive integer.") }
            return
        }
        val normalizedTimeoutSeconds = timeoutSeconds?.takeIf { it > 0 } ?: OPENAI_DEFAULT_TIMEOUT_SECONDS

        viewModelScope.launch {
            settingsDataStore.saveProviderSettings(
                ProviderSettingsSnapshot(
                    providerType = snapshot.providerType,
                    openAiBaseUrl = snapshot.openAiBaseUrl,
                    openAiModelId = snapshot.openAiModelId,
                    openAiTimeoutSeconds = normalizedTimeoutSeconds,
                ),
            )
            if (snapshot.apiKeyDraft.isNotBlank()) {
                providerSecretStore.writeApiKey(
                    providerType = ProviderType.OpenAiCompatible,
                    apiKey = snapshot.apiKeyDraft,
                )
            }
            refresh(statusMessage = "Provider settings saved.")
        }
    }

    private fun refresh(statusMessage: String? = null) {
        viewModelScope.launch {
            val settings = settingsDataStore.settings.first()
            val storedApiKey = providerSecretStore.readApiKey(ProviderType.OpenAiCompatible)
            mutableState.value = SettingsUiState(
                activeProviderId = providerRegistry.require(settings.providerType).id,
                availableProviders = providerRegistry.descriptors().map { it.type },
                providerType = settings.providerType,
                openAiBaseUrl = settings.openAiBaseUrl,
                openAiModelId = settings.openAiModelId,
                openAiTimeoutSeconds = settings.openAiTimeoutSeconds.toString(),
                apiKeyDraft = "",
                hasStoredApiKey = !storedApiKey.isNullOrBlank(),
                configured = isConfigured(
                    providerType = settings.providerType,
                    baseUrl = settings.openAiBaseUrl,
                    modelId = settings.openAiModelId,
                    apiKeyDraft = "",
                    hasStoredApiKey = !storedApiKey.isNullOrBlank(),
                ),
                statusMessage = statusMessage,
                buildPosture = "Single-app-module, manual DI, Compose navigation shell, FakeProvider plus OpenAI-compatible provider.",
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
        return when (providerType) {
            ProviderType.Fake -> true
            ProviderType.OpenAiCompatible -> {
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
