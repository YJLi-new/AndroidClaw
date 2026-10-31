package ai.androidclaw.ui.navigation

import ai.androidclaw.app.AppContainer
import ai.androidclaw.feature.chat.ChatScreen
import ai.androidclaw.feature.chat.ChatViewModel
import ai.androidclaw.feature.health.DiagnosticsScreen
import ai.androidclaw.feature.health.HealthViewModel
import ai.androidclaw.feature.onboarding.OnboardingDialog
import ai.androidclaw.feature.onboarding.OnboardingViewModel
import ai.androidclaw.feature.settings.SettingsScreen
import ai.androidclaw.feature.settings.SettingsViewModel
import ai.androidclaw.feature.skills.SkillDetailScreen
import ai.androidclaw.feature.skills.SkillsScreen
import ai.androidclaw.feature.skills.SkillsViewModel
import ai.androidclaw.feature.tasks.TaskEditorScreen
import ai.androidclaw.feature.tasks.TasksScreen
import ai.androidclaw.feature.tasks.TasksViewModel
import ai.androidclaw.ui.theme.AndroidClawTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun AndroidClawApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val settingsViewModel: SettingsViewModel =
        viewModel(factory = SettingsViewModel.factory(container.settingsDependencies))
    val onboardingViewModel: OnboardingViewModel =
        viewModel(factory = OnboardingViewModel.factory(container.onboardingDependencies))
    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
    val onboardingState by onboardingViewModel.state.collectAsStateWithLifecycle()
    val showBottomBar = backStackEntry?.destination?.route in Routes.topLevelRoutes

    AndroidClawTheme(themePreference = settingsState.themePreference) {
        Box {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    if (showBottomBar) {
                        AppNavigationBar(navController = navController, backStackEntry = backStackEntry)
                    }
                },
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = TopLevelDestination.Chat.route,
                    modifier =
                        Modifier
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding),
                ) {
                    navigation(route = TopLevelDestination.Chat.route, startDestination = Routes.CHAT_HOME) {
                        composable(Routes.CHAT_HOME) {
                            val viewModel: ChatViewModel =
                                viewModel(factory = ChatViewModel.factory(container.chatDependencies))
                            ChatScreen(
                                viewModel = viewModel,
                                onOpenProviderSettings = { navController.navigateToTopLevel(TopLevelDestination.Settings) },
                            )
                        }
                    }
                    navigation(route = TopLevelDestination.Tasks.route, startDestination = Routes.TASKS_HOME) {
                        composable(Routes.TASKS_HOME) { entry ->
                            val viewModel: TasksViewModel =
                                navController.graphViewModel(
                                    entry,
                                    TopLevelDestination.Tasks.route,
                                    TasksViewModel.factory(container.tasksDependencies),
                                )
                            TasksScreen(
                                viewModel = viewModel,
                                onCreateTask = { navController.navigate(Routes.taskEditor(null)) },
                                onEditTask = { taskId -> navController.navigate(Routes.taskEditor(taskId)) },
                            )
                        }
                        composable(
                            route = Routes.TASK_EDITOR,
                            arguments =
                                listOf(
                                    navArgument(Routes.ARG_TASK_ID) {
                                        type = NavType.StringType
                                        nullable = true
                                        defaultValue = null
                                    },
                                ),
                        ) { entry ->
                            val viewModel: TasksViewModel =
                                navController.graphViewModel(
                                    entry,
                                    TopLevelDestination.Tasks.route,
                                    TasksViewModel.factory(container.tasksDependencies),
                                )
                            TaskEditorScreen(
                                viewModel = viewModel,
                                taskId = entry.arguments?.getString(Routes.ARG_TASK_ID),
                                onClose = { navController.popBackStack() },
                            )
                        }
                    }
                    navigation(route = TopLevelDestination.Skills.route, startDestination = Routes.SKILLS_HOME) {
                        composable(Routes.SKILLS_HOME) { entry ->
                            val viewModel: SkillsViewModel =
                                navController.graphViewModel(
                                    entry,
                                    TopLevelDestination.Skills.route,
                                    SkillsViewModel.factory(container.skillsDependencies),
                                )
                            SkillsScreen(
                                viewModel = viewModel,
                                onOpenSkill = { skillId -> navController.navigate(Routes.skillDetail(skillId)) },
                            )
                        }
                        composable(
                            route = Routes.SKILL_DETAIL,
                            arguments = listOf(navArgument(Routes.ARG_SKILL_ID) { type = NavType.StringType }),
                        ) { entry ->
                            val viewModel: SkillsViewModel =
                                navController.graphViewModel(
                                    entry,
                                    TopLevelDestination.Skills.route,
                                    SkillsViewModel.factory(container.skillsDependencies),
                                )
                            SkillDetailScreen(
                                viewModel = viewModel,
                                skillId = entry.arguments?.getString(Routes.ARG_SKILL_ID).orEmpty(),
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                    navigation(route = TopLevelDestination.Settings.route, startDestination = Routes.SETTINGS_HOME) {
                        composable(Routes.SETTINGS_HOME) {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onOpenSetupGuide = onboardingViewModel::showProviderSetup,
                                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                            )
                        }
                        composable(Routes.DIAGNOSTICS) {
                            val viewModel: HealthViewModel =
                                viewModel(factory = HealthViewModel.factory(container.healthDependencies))
                            DiagnosticsScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
            if (onboardingState.visible) {
                OnboardingDialog(
                    onboardingState = onboardingState,
                    settingsState = settingsState,
                    onConfigureRealProvider = {
                        onboardingViewModel.showProviderSetup()
                        navController.navigateToTopLevel(TopLevelDestination.Settings)
                    },
                    onUseFakeMode = onboardingViewModel::useFakeMode,
                    onCompleteLater = onboardingViewModel::completeLater,
                    onBackToWelcome = onboardingViewModel::showWelcome,
                    onSelectProvider = settingsViewModel::selectProviderType,
                    onBaseUrlChanged = settingsViewModel::onBaseUrlChanged,
                    onModelIdChanged = settingsViewModel::onModelIdChanged,
                    onTimeoutChanged = settingsViewModel::onTimeoutChanged,
                    onApiKeyChanged = settingsViewModel::onApiKeyChanged,
                    onStartOpenAiCodexSignIn = settingsViewModel::startOpenAiCodexDeviceCodeSignIn,
                    onCancelOpenAiCodexSignIn = settingsViewModel::cancelOpenAiCodexDeviceCodeSignIn,
                    onClearOpenAiCodexSignIn = settingsViewModel::clearOpenAiCodexSignIn,
                    onValidateConnection = settingsViewModel::validateConnection,
                    onFinish = onboardingViewModel::finish,
                )
            }
        }
    }
}

@Composable
private fun AppNavigationBar(
    navController: NavHostController,
    backStackEntry: NavBackStackEntry?,
) {
    NavigationBar(modifier = Modifier.testTag("topLevelNav")) {
        TopLevelDestination.entries.forEach { destination ->
            val selected =
                backStackEntry
                    ?.destination
                    ?.hierarchy
                    ?.any { it.route == destination.route } == true
            NavigationBarItem(
                modifier = Modifier.testTag("navItem-${destination.route}"),
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigateToTopLevel(destination)
                    }
                },
                icon = {
                    Icon(
                        painter = painterResource(destination.iconRes),
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(destination.labelRes)) },
            )
        }
    }
}

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.startDestinationId) {
            saveState = true
        }
    }
}

/** A ViewModel shared by every destination inside the nested graph [graphRoute]. */
@Composable
private inline fun <reified VM : ViewModel> NavHostController.graphViewModel(
    entry: NavBackStackEntry,
    graphRoute: String,
    factory: ViewModelProvider.Factory,
): VM {
    val parentEntry = remember(entry) { getBackStackEntry(graphRoute) }
    return viewModel(viewModelStoreOwner = parentEntry, factory = factory)
}
