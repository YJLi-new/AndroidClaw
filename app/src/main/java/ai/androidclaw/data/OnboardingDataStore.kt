package ai.androidclaw.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.onboardingDataStore by preferencesDataStore(name = "androidclaw_onboarding")

class OnboardingDataStore(
    private val context: Context,
) {
    private val completedKey = booleanPreferencesKey("completed")

    val completed: Flow<Boolean> = context.onboardingDataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            preferences[completedKey] ?: false
        }

    suspend fun setCompleted(completed: Boolean) {
        context.onboardingDataStore.edit { preferences ->
            preferences[completedKey] = completed
        }
    }
}
