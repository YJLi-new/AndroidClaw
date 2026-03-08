package ai.androidclaw.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class SettingsSnapshot(
    val providerType: String,
)

private val Context.settingsDataStore by preferencesDataStore(name = "androidclaw_settings")

class SettingsDataStore(
    private val context: Context,
) {
    private val providerTypeKey = stringPreferencesKey("provider_type")
    // Provider credentials must use a separate Keystore-backed store once remote providers are added.

    val settings: Flow<SettingsSnapshot> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            SettingsSnapshot(
                providerType = preferences[providerTypeKey] ?: "fake",
            )
        }

    suspend fun setProviderType(providerType: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[providerTypeKey] = providerType
        }
    }
}
