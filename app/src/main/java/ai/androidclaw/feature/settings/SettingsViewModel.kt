package ai.androidclaw.feature.settings

import ai.androidclaw.app.SettingsDependencies
import ai.androidclaw.data.ProviderEndpointSettings
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderSettingsSnapshot
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.runtime.providers.ModelMessage
import ai.androidclaw.runtime.providers.ModelMessageRole
import ai.androidclaw.runtime.providers.ModelProviderException
import ai.androidclaw.runtime.providers.ModelProviderFailureKind
import ai.androidclaw.runtime.providers.ModelRequest
import ai.androidclaw.runtime.providers.ModelRunMode
import ai.androidclaw.runtime.providers.NetworkStatusProvider
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
    val networkSummary: String = "",
    val apiKeyDraft: String = "",
    val hasStoredApiKey: Boolean = false,
    val configured: Boolean = false,
    val isValidatingConnection: Boolean = false,
    val lastValidationSucceeded: Boolean = false,
    val statusMessage: String? = null,
    val buildPosture: String = "",
)

class SettingsViewModel(
    private val providerRegistry: ProviderRegistry,
    private val settingsDataStore: SettingsDataStore,
    private val providerSecretStore: ProviderSecretStore,
    private val networkStatusProvider: NetworkStatusProvider,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SettingsUiState(
            availableProviders = providerRegistry.descriptors().map { it.type },
            buildPosture = "Single-app-module, manual DI, Compose navigation shell, FakeProvider plus OpenAI-compatible presets and native Claude.",
        ),
    )
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    private var stateMutationVersion: Long = 0

    init {
        refresh()
    }

    fun selectProviderType(providerType: ProviderType) {
        val mutationVersion = nextMutationVersion()
        viewModelScope.launch {
            val settings = settingsDataStore.settings.first()
            val storedApiKey = providerSecretStore.readApiKey(providerType)
            val recoveredApiKey = providerSecretStore.consumeRecoveryNotice(providerType)
            val endpointSettings = settings.endpointSettings(providerType)
            val networkStatus = networkStatusProvider.currentStatus()
            if (!isCurrentMutation(mutationVersion)) {
                return@launch
            }
            mutableState.update {
                it.copy(
                    providerType = providerType,
                    activeProviderId = providerRegistry.require(providerType).id,
                    baseUrl = endpointSettings.baseUrl,
                    modelId = endpointSettings.modelId,
                    timeoutSeconds = endpointSettings.timeoutSeconds.toString(),
                    networkSummary = networkStatus.summary,
                    apiKeyDraft = "",
                    hasStoredApiKey = !storedApiKey.isNullOrBlank(),
                    configured = isConfigured(
                        providerType = providerType,
                        baseUrl = endpointSettings.baseUrl,
                        modelId = endpointSettings.modelId,
                        apiKeyDraft = "",
                        hasStoredApiKey = !storedApiKey.isNullOrBlank(),
                    ),
                    isValidatingConnection = false,
                    lastValidationSucceeded = false,
                    statusMessage = resolveStatusMessage(
                        explicit = null,
                        providerType = providerType,
                        recoveredApiKey = recoveredApiKey,
                        networkConnected = networkStatus.isConnected,
                    ),
                )
            }
        }
    }

    fun onBaseUrlChanged(value: String) {
        mutateState {
            it.copy(
                baseUrl = value,
                configured = isConfigured(
                    providerType = it.providerType,
                    baseUrl = value,
                    modelId = it.modelId,
                    apiKeyDraft = it.apiKeyDraft,
                    hasStoredApiKey = it.hasStoredApiKey,
                ),
                lastValidationSucceeded = false,
                statusMessage = null,
            )
        }
    }

    fun onModelIdChanged(value: String) {
        mutateState {
            it.copy(
                modelId = value,
                configured = isConfigured(
                    providerType = it.providerType,
                    baseUrl = it.baseUrl,
                    modelId = value,
                    apiKeyDraft = it.apiKeyDraft,
                    hasStoredApiKey = it.hasStoredApiKey,
                ),
                lastValidationSucceeded = false,
                statusMessage = null,
            )
        }
    }

    fun onTimeoutChanged(value: String) {
        mutateState {
            it.copy(
                timeoutSeconds = value,
                lastValidationSucceeded = false,
                statusMessage = null,
            )
        }
    }

    fun onApiKeyChanged(value: String) {
        mutateState {
            it.copy(
                apiKeyDraft = value,
                configured = isConfigured(
                    providerType = it.providerType,
                    baseUrl = it.baseUrl,
                    modelId = it.modelId,
                    apiKeyDraft = value,
                    hasStoredApiKey = it.hasStoredApiKey,
                ),
                lastValidationSucceeded = false,
                statusMessage = null,
            )
        }
    }

    fun clearStoredApiKey() {
        val providerType = state.value.providerType
        if (!providerType.requiresRemoteSettings) {
            return
        }
        val mutationVersion = nextMutationVersion()
        viewModelScope.launch {
            providerSecretStore.writeApiKey(providerType, null)
            if (!isCurrentMutation(mutationVersion)) {
                return@launch
            }
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
                    lastValidationSucceeded = false,
                    statusMessage = "Stored API key cleared.",
                )
            }
        }
    }

    fun save() {
        val snapshot = state.value
        val timeoutSeconds = snapshot.timeoutSeconds.toIntOrNull()
        if (snapshot.providerType.requiresRemoteSettings && (timeoutSeconds == null || timeoutSeconds <= 0)) {
            mutateState { it.copy(statusMessage = "Timeout must be a positive integer.") }
            return
        }
        val normalizedTimeoutSeconds = timeoutSeconds?.takeIf { it > 0 }
            ?: snapshot.providerType.defaultTimeoutSeconds
        val mutationVersion = nextMutationVersion()

        viewModelScope.launch {
            persistSettings(snapshot, normalizedTimeoutSeconds)
            if (!isCurrentMutation(mutationVersion)) {
                return@launch
            }
            refresh(statusMessage = "Provider settings saved.")
        }
    }

    fun validateConnection() {
        val snapshot = state.value
        val timeoutSeconds = snapshot.timeoutSeconds.toIntOrNull()
        if (snapshot.providerType.requiresRemoteSettings && (timeoutSeconds == null || timeoutSeconds <= 0)) {
            mutateState {
                it.copy(
                    isValidatingConnection = false,
                    lastValidationSucceeded = false,
                    statusMessage = "Timeout must be a positive integer.",
                )
            }
            return
        }
        if (snapshot.providerType == ProviderType.Fake) {
            mutateState {
                it.copy(
                    isValidatingConnection = false,
                    lastValidationSucceeded = true,
                    statusMessage = "FakeProvider is ready. It works offline.",
                )
            }
            return
        }
        val normalizedTimeoutSeconds = timeoutSeconds?.takeIf { it > 0 }
            ?: snapshot.providerType.defaultTimeoutSeconds
        val mutationVersion = nextMutationVersion()

        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isValidatingConnection = true,
                    lastValidationSucceeded = false,
                    statusMessage = "Testing connection…",
                )
            }
            try {
                persistSettings(snapshot, normalizedTimeoutSeconds)
                providerRegistry.require(snapshot.providerType).generate(
                    ModelRequest(
                        sessionId = "provider-validation",
                        requestId = "provider-validation",
                        messageHistory = listOf(
                            ModelMessage(
                                role = ModelMessageRole.User,
                                content = "Reply with OK.",
                            ),
                        ),
                        systemPrompt = "You are validating AndroidClaw connectivity. Reply briefly with OK.",
                        enabledSkills = emptyList(),
                        toolDescriptors = emptyList(),
                        runMode = ModelRunMode.Interactive,
                    ),
                )
                if (!isCurrentMutation(mutationVersion)) {
                    return@launch
                }
                mutableState.update {
                    it.copy(
                        isValidatingConnection = false,
                        lastValidationSucceeded = true,
                        configured = true,
                        statusMessage = "Connection test succeeded.",
                    )
                }
            } catch (error: ModelProviderException) {
                if (!isCurrentMutation(mutationVersion)) {
                    return@launch
                }
                mutableState.update {
                    it.copy(
                        isValidatingConnection = false,
                        lastValidationSucceeded = false,
                        statusMessage = validationFailureMessage(error),
                    )
                }
            } catch (error: Exception) {
                if (!isCurrentMutation(mutationVersion)) {
                    return@launch
                }
                mutableState.update {
                    it.copy(
                        isValidatingConnection = false,
                        lastValidationSucceeded = false,
                        statusMessage = error.message ?: "Connection test failed.",
                    )
                }
            }
        }
    }

    private fun refresh(statusMessage: String? = null) {
        viewModelScope.launch {
            val refreshVersion = stateMutationVersion
            val settings = settingsDataStore.settings.first()
            val providerType = settings.providerType
            val endpointSettings = settings.endpointSettings(providerType)
            val storedApiKey = providerSecretStore.readApiKey(providerType)
            val recoveredApiKey = providerSecretStore.consumeRecoveryNotice(providerType)
            val networkStatus = networkStatusProvider.currentStatus()
            if (!isCurrentMutation(refreshVersion)) {
                return@launch
            }
            mutableState.value = SettingsUiState(
                activeProviderId = providerRegistry.require(providerType).id,
                availableProviders = providerRegistry.descriptors().map { it.type },
                providerType = providerType,
                baseUrl = endpointSettings.baseUrl,
                modelId = endpointSettings.modelId,
                timeoutSeconds = endpointSettings.timeoutSeconds.toString(),
                networkSummary = networkStatus.summary,
                apiKeyDraft = "",
                hasStoredApiKey = !storedApiKey.isNullOrBlank(),
                configured = isConfigured(
                    providerType = providerType,
                    baseUrl = endpointSettings.baseUrl,
                    modelId = endpointSettings.modelId,
                    apiKeyDraft = "",
                    hasStoredApiKey = !storedApiKey.isNullOrBlank(),
                ),
                isValidatingConnection = false,
                lastValidationSucceeded = false,
                statusMessage = resolveStatusMessage(
                    explicit = statusMessage,
                    providerType = providerType,
                    recoveredApiKey = recoveredApiKey,
                    networkConnected = networkStatus.isConnected,
                ),
                buildPosture = "Single-app-module, manual DI, Compose navigation shell, FakeProvider plus OpenAI-compatible presets and native Claude.",
            )
        }
    }

    private fun mutateState(transform: (SettingsUiState) -> SettingsUiState) {
        nextMutationVersion()
        mutableState.update(transform)
    }

    private fun nextMutationVersion(): Long {
        stateMutationVersion += 1
        return stateMutationVersion
    }

    private fun isCurrentMutation(version: Long): Boolean {
        return version == stateMutationVersion
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

    private suspend fun persistSettings(
        snapshot: SettingsUiState,
        normalizedTimeoutSeconds: Int,
    ) {
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
    }

    private fun resolveStatusMessage(
        explicit: String?,
        providerType: ProviderType,
        recoveredApiKey: Boolean,
        networkConnected: Boolean,
    ): String? {
        return when {
            !explicit.isNullOrBlank() -> explicit
            recoveredApiKey -> "Stored API key could not be restored on this device. Please enter it again."
            providerType.requiresRemoteSettings && !networkConnected ->
                "No active network connection. Remote providers may fail until connectivity returns."
            else -> null
        }
    }

    private fun validationFailureMessage(error: ModelProviderException): String {
        return when (error.kind) {
            ModelProviderFailureKind.Configuration -> error.userMessage
            ModelProviderFailureKind.Authentication -> "Authentication failed. Check the API key."
            ModelProviderFailureKind.Network ->
                "Network error. Check your connection and the provider base URL."
            ModelProviderFailureKind.Timeout ->
                "Connection test timed out. Check the endpoint and timeout."
            ModelProviderFailureKind.Response ->
                error.userMessage.ifBlank { "Provider returned an unexpected response." }
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
                        networkStatusProvider = dependencies.networkStatusProvider,
                    ) as T
                }
            }
        }
    }
}
