package ai.androidclaw.ui.navigation

import androidx.annotation.DrawableRes
import ai.androidclaw.R

enum class TopLevelDestination(
    val route: String,
    val label: String,
    @param:DrawableRes val iconRes: Int,
) {
    Chat(route = "chat", label = "Chat", iconRes = R.drawable.ic_nav_chat),
    Tasks(route = "tasks", label = "Tasks", iconRes = R.drawable.ic_nav_tasks),
    Skills(route = "skills", label = "Skills", iconRes = R.drawable.ic_nav_skills),
    Settings(route = "settings", label = "Settings", iconRes = R.drawable.ic_nav_settings),
    Health(route = "health", label = "Health", iconRes = R.drawable.ic_nav_health),
}
