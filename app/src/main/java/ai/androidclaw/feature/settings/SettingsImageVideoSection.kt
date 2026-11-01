package ai.androidclaw.feature.settings

import ai.androidclaw.BuildConfig
import ai.androidclaw.R
import ai.androidclaw.data.PROVIDER_MODEL_ID_MAX_CHARS
import ai.androidclaw.data.ProviderSecretStore
import ai.androidclaw.data.ProviderType
import ai.androidclaw.data.SettingsDataStore
import ai.androidclaw.runtime.media.MediaBackendMode
import ai.androidclaw.runtime.media.resolveMediaBackendMode
import ai.androidclaw.ui.components.ClawSectionHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImageVideoSettingsState(
    val mode: MediaBackendMode = MediaBackendMode.Off,
    val imageModelId: String = "",
    val videoModelId: String = "",
    val imageModelInput: String? = null,
    val videoModelInput: String? = null,
    val saved: Boolean = false,
)

private data class ImageVideoLocalState(
    val imageModelInput: String? = null,
    val videoModelInput: String? = null,
    val saved: Boolean = false,
)

/**
 * Settings → Image and video. The OpenAI key is only checked for presence, once the section is
 * collected, so opening the app never touches the encrypted store.
 */
class ImageVideoSettingsModel(
    private val scope: CoroutineScope,
    private val settingsDataStore: SettingsDataStore,
    private val providerSecretStore: ProviderSecretStore,
    private val fakeBackendAllowed: Boolean = BuildConfig.DEBUG,
) {
    private val keyPresent = MutableStateFlow<Boolean?>(null)
    private val local = MutableStateFlow(ImageVideoLocalState())

    val state: StateFlow<ImageVideoSettingsState> =
        combine(
            settingsDataStore.settings,
            settingsDataStore.imageModelId,
            settingsDataStore.videoModelId,
            keyPresent,
            local,
        ) { settings, imageModel, videoModel, hasKey, localState ->
            ImageVideoSettingsState(
                mode = resolveMediaBackendMode(settings.providerType, hasKey == true, fakeBackendAllowed),
                imageModelId = imageModel,
                videoModelId = videoModel,
                imageModelInput = localState.imageModelInput,
                videoModelInput = localState.videoModelInput,
                saved = localState.saved,
            )
        }.onStart {
            scope.launch { keyPresent.value = !providerSecretStore.readApiKey(ProviderType.OpenAi).isNullOrBlank() }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ImageVideoSettingsState())

    fun onImageModelChanged(value: String) {
        local.update { it.copy(imageModelInput = value.take(PROVIDER_MODEL_ID_MAX_CHARS), saved = false) }
    }

    fun onVideoModelChanged(value: String) {
        local.update { it.copy(videoModelInput = value.take(PROVIDER_MODEL_ID_MAX_CHARS), saved = false) }
    }

    fun save() {
        val pending = local.value
        scope.launch {
            pending.imageModelInput?.let { settingsDataStore.setImageModelId(it) }
            pending.videoModelInput?.let { settingsDataStore.setVideoModelId(it) }
            local.value = ImageVideoLocalState(saved = true)
        }
    }
}

/** The section with its header; SettingsScreen has one call site. */
@Composable
internal fun SettingsImageVideoSection(model: ImageVideoSettingsModel) {
    val state by model.state.collectAsStateWithLifecycle()
    ClawSectionHeader(stringResource(R.string.settings_section_image_video))
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("imageVideoSection"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text =
                stringResource(
                    when (state.mode) {
                        MediaBackendMode.OpenAi -> R.string.settings_image_video_mode_openai
                        MediaBackendMode.Fake -> R.string.settings_image_video_mode_fake
                        MediaBackendMode.Off -> R.string.settings_image_video_mode_off
                    },
                ),
            modifier = Modifier.testTag("imageVideoModeText"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.imageModelInput ?: state.imageModelId,
            onValueChange = model::onImageModelChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("imageVideoImageModel"),
            label = { Text(stringResource(R.string.settings_image_video_image_model)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.videoModelInput ?: state.videoModelId,
            onValueChange = model::onVideoModelChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("imageVideoVideoModel"),
            label = { Text(stringResource(R.string.settings_image_video_video_model)) },
            supportingText = { Text(stringResource(R.string.settings_image_video_cost_note)) },
            singleLine = true,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = model::save,
                enabled = state.imageModelInput != null || state.videoModelInput != null,
                modifier = Modifier.testTag("imageVideoSave"),
            ) { Text(stringResource(R.string.settings_image_video_save)) }
            if (state.saved) {
                Text(
                    text = stringResource(R.string.settings_image_video_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
