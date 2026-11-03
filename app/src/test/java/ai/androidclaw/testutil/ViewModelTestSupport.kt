package ai.androidclaw.testutil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job

/**
 * Cancels this ViewModel's scope and waits until its coroutines have finished.
 *
 * Tests never clear their ViewModels, so work launched from `init` can still be
 * resuming on `Dispatchers.Main` from Room or IO threads after the test ends.
 * When such a resume lands while the next test's [MainDispatcherRule] swaps
 * Main, `TestMainDispatcher` reads the real Main for `isDispatchNeeded` and the
 * new `UnconfinedTestDispatcher` for `dispatch`, which throws
 * `UnsupportedOperationException` into the next test.
 */
suspend fun ViewModel.cancelAndJoinScope() {
    viewModelScope.coroutineContext.job.cancelAndJoin()
}
