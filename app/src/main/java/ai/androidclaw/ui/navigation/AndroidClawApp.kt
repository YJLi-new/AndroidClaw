package ai.androidclaw.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import ai.androidclaw.feature.onboarding.OnboardingDialog
import ai.androidclaw.feature.onboarding.OnboardingViewModel
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
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(container.settingsDependencies),
    )
    val onboardingViewModel: OnboardingViewModel = viewModel(
        factory = OnboardingViewModel.factory(container.onboardingDependencies),
    )
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    val onboardingState by onboardingViewModel.state.collectAsStateWithLifecycle()

    Box {
        Scaffold(
            bottomBar = {
                NavigationBar(modifier = Modifier.testTag("topLevelNav")) {
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
            }
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
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onOpenSetupGuide = onboardingViewModel::showProviderSetup,
                    )
                }
                composable(TopLevelDestination.Health.route) {
                    val viewModel: HealthViewModel = viewModel(
                        factory = HealthViewModel.factory(container.healthDependencies),
                    )
                    HealthScreen(viewModel = viewModel)
                }
            }
        }
        if (onboardingState.visible) {
            OnboardingDialog(
                onboardingState = onboardingState,
                settingsState = settingsState,
                onConfigureRealProvider = {
                    onboardingViewModel.showProviderSetup()
                    navController.navigate(TopLevelDestination.Settings.route) {
                        launchSingleTop = true
                    }
                },
                onUseFakeMode = onboardingViewModel::useFakeMode,
                onCompleteLater = onboardingViewModel::completeLater,
                onBackToWelcome = onboardingViewModel::showWelcome,
                onSelectProvider = settingsViewModel::selectProviderType,
                onBaseUrlChanged = settingsViewModel::onBaseUrlChanged,
                onModelIdChanged = settingsViewModel::onModelIdChanged,
                onTimeoutChanged = settingsViewModel::onTimeoutChanged,
                onApiKeyChanged = settingsViewModel::onApiKeyChanged,
                onValidateConnection = settingsViewModel::validateConnection,
                onFinish = onboardingViewModel::finish,
            )
        }
    }
}
