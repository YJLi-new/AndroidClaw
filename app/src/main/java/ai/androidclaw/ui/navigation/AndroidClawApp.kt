package ai.androidclaw.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.androidclaw.app.AppContainer
import ai.androidclaw.feature.chat.ChatScreen
import ai.androidclaw.feature.chat.ChatViewModel
import ai.androidclaw.feature.health.HealthScreen
import ai.androidclaw.feature.health.HealthViewModel
import ai.androidclaw.feature.settings.SettingsScreen
import ai.androidclaw.feature.settings.SettingsViewModel
import ai.androidclaw.feature.skills.SkillsScreen
import ai.androidclaw.feature.skills.SkillsViewModel
import ai.androidclaw.feature.tasks.TasksScreen
import ai.androidclaw.feature.tasks.TasksViewModel

@Composable
fun AndroidClawApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = backStackEntry
                        ?.destination
                        ?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                    restoreState = true
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                }
                            }
                        },
                        icon = { Text(destination.glyph) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Chat.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.Chat.route) {
                val viewModel: ChatViewModel = viewModel(
                    factory = ChatViewModel.factory(container.chatDependencies),
                )
                ChatScreen(viewModel = viewModel)
            }
            composable(TopLevelDestination.Tasks.route) {
                val viewModel: TasksViewModel = viewModel(
                    factory = TasksViewModel.factory(container.tasksDependencies),
                )
                TasksScreen(viewModel = viewModel)
            }
            composable(TopLevelDestination.Skills.route) {
                val viewModel: SkillsViewModel = viewModel(
                    factory = SkillsViewModel.factory(container.skillsDependencies),
                )
                SkillsScreen(viewModel = viewModel)
            }
            composable(TopLevelDestination.Settings.route) {
                val viewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.factory(container.settingsDependencies),
                )
                SettingsScreen(viewModel = viewModel)
            }
            composable(TopLevelDestination.Health.route) {
                val viewModel: HealthViewModel = viewModel(
                    factory = HealthViewModel.factory(container.healthDependencies),
                )
                HealthScreen(viewModel = viewModel)
            }
        }
    }
}
