package ai.androidclaw.feature.onboarding

import ai.androidclaw.app.OnboardingDependencies
import ai.androidclaw.app.viewModelFactory
import ai.androidclaw.data.OnboardingDataStore
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep {
    Welcome,
    ProviderSetup,
}

data class OnboardingUiState(
    val visible: Boolean = false,
    val step: OnboardingStep = OnboardingStep.Welcome,
) {
    val isWelcomeStep: Boolean
        get() = step == OnboardingStep.Welcome
}

class OnboardingViewModel(
    private val onboardingDataStore: OnboardingDataStore,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val completed = onboardingDataStore.completed.first()
            val providerType = settingsDataStore.settings.first().providerType
            if (!completed && providerType == ProviderType.Fake) {
                mutableState.value =
                    OnboardingUiState(
                        visible = true,
                        step = OnboardingStep.Welcome,
                    )
            }
        }
    }

    fun showWelcome() {
        mutableState.update {
            it.copy(
                visible = true,
                step = OnboardingStep.Welcome,
            )
        }
    }

    fun showProviderSetup() {
        mutableState.update {
            it.copy(
                visible = true,
                step = OnboardingStep.ProviderSetup,
            )
        }
    }

    fun useFakeMode() {
        viewModelScope.launch {
            settingsDataStore.setProviderType(ProviderType.Fake)
            onboardingDataStore.setCompleted(true)
            mutableState.value = OnboardingUiState()
        }
    }

    fun completeLater() {
        viewModelScope.launch {
            onboardingDataStore.setCompleted(true)
            mutableState.value = OnboardingUiState()
        }
    }

    fun finish() {
        viewModelScope.launch {
            onboardingDataStore.setCompleted(true)
            mutableState.value = OnboardingUiState()
        }
    }

    companion object {
        fun factory(dependencies: OnboardingDependencies) =
            viewModelFactory {
                OnboardingViewModel(
                    onboardingDataStore = dependencies.onboardingDataStore,
                    settingsDataStore = dependencies.settingsDataStore,
                )
            }
    }
}
