package ai.androidclaw.ui.navigation

import ai.androidclaw.R
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** Bottom-bar destinations. Each is a nested graph whose start route is [startRoute]. */
enum class TopLevelDestination(
    val route: String,
    val startRoute: String,
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int,
) {
    Chat(route = "chat", startRoute = Routes.CHAT_HOME, labelRes = R.string.nav_chat, iconRes = R.drawable.ic_nav_chat),
    Tasks(route = "tasks", startRoute = Routes.TASKS_HOME, labelRes = R.string.nav_tasks, iconRes = R.drawable.ic_nav_tasks),
    Skills(route = "skills", startRoute = Routes.SKILLS_HOME, labelRes = R.string.nav_skills, iconRes = R.drawable.ic_nav_skills),
    Settings(
        route = "settings",
        startRoute = Routes.SETTINGS_HOME,
        labelRes = R.string.nav_settings,
        iconRes = R.drawable.ic_nav_settings,
    ),
}

object Routes {
    const val CHAT_HOME = "chat/home"
    const val TASKS_HOME = "tasks/home"
    const val TASK_EDITOR = "tasks/edit?taskId={taskId}"
    const val SKILLS_HOME = "skills/home"
    const val SKILL_DETAIL = "skills/detail/{skillId}"
    const val SETTINGS_HOME = "settings/home"
    const val DIAGNOSTICS = "settings/diagnostics"
    const val WORKSPACE = "settings/workspace"
    const val WORKSPACE_EDITOR = "settings/workspace/edit/{path}"

    const val ARG_TASK_ID = "taskId"
    const val ARG_SKILL_ID = "skillId"
    const val ARG_WORKSPACE_PATH = "path"

    /** Routes that show the bottom navigation bar. */
    val topLevelRoutes = setOf(CHAT_HOME, TASKS_HOME, SKILLS_HOME, SETTINGS_HOME)

    fun taskEditor(taskId: String?): String = if (taskId == null) "tasks/edit" else "tasks/edit?taskId=${Uri.encode(taskId)}"

    fun skillDetail(skillId: String): String = "skills/detail/${Uri.encode(skillId)}"

    fun workspaceEditor(path: String): String = "settings/workspace/edit/${Uri.encode(path)}"
}
