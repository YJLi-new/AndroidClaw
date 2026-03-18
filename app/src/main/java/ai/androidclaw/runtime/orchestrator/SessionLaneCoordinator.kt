package ai.androidclaw.runtime.orchestrator

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class SessionLaneCoordinator {
    private val lanes = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLane(
        sessionId: String,
        block: suspend () -> T,
    ): T {
        val lane = lanes.getOrPut(sessionId) { Mutex() }
        return lane.withLock {
            block()
        }
    }
}
