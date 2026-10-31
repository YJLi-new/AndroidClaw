package ai.androidclaw.runtime.device

import android.app.ActivityManager

/**
 * Answers whether AndroidClaw is currently visible to the user, so foreground-only tools
 * (activity intents, clipboard) can fail cleanly from background work. It asks the platform
 * for this process's importance on demand: no callbacks to register and no polling.
 */
object AppForegroundState {
    fun isInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance in 1..ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }
}
