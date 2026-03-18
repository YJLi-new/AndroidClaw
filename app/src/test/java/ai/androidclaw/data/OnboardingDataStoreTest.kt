package ai.androidclaw.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingDataStoreTest {
    @Test
    fun completedFlag_roundTrips() =
        runTest {
            val application = ApplicationProvider.getApplicationContext<android.app.Application>()
            val dataStore = OnboardingDataStore(application)

            dataStore.setCompleted(false)
            assertFalse(dataStore.completed.first())

            dataStore.setCompleted(true)
            assertTrue(dataStore.completed.first())

            dataStore.setCompleted(false)
        }
}
