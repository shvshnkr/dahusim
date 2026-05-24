@file:OptIn(KoinExperimentalAPI::class)

package fr.husi.di

import androidx.navigation3.runtime.NavKey
import fr.husi.results.LocalResultEventBus
import fr.husi.ui.AboutScreen
import fr.husi.ui.AppUpdateScreen
import fr.husi.ui.AssetEditScreen
import fr.husi.ui.AssetsScreen
import fr.husi.ui.GroupScreen
import fr.husi.ui.GroupSettingsScreen
import fr.husi.ui.LibrariesScreen
import fr.husi.ui.DrawerController
import fr.husi.ui.LogcatScreen
import fr.husi.ui.Navigator
import fr.husi.ui.MainScreenScope
import fr.husi.ui.MainViewModel
import fr.husi.ui.NavRoutes
import fr.husi.ui.PluginScreen
import fr.husi.ui.ProfilePickerController
import fr.husi.ui.RouteScreen
import fr.husi.ui.RouteSettingsScreen
import fr.husi.ui.QuickSettingsScreen
import fr.husi.ui.SettingsScreen
import fr.husi.database.DataStore
import fr.husi.ui.configuration.ConfigurationScreen
import fr.husi.ui.dashboard.ConnectionDetailScreen
import fr.husi.ui.dashboard.DashboardScreen
import fr.husi.ui.profile.ConfigEditScreen
import fr.husi.ui.profile.ProfileEditorScreen
import fr.husi.ui.simple.SimpleHomeScreen
import fr.husi.ui.tools.GetCertScreen
import fr.husi.ui.tools.RuleSetMatchScreen
import fr.husi.ui.tools.SpeedtestScreen
import fr.husi.ui.tools.StunScreen
import fr.husi.ui.tools.ToolsScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

internal val commonNavigationModule = module {
    scope<MainScreenScope> {
        viewModelOf(::MainViewModel)
        scoped { params ->
            Navigator(
                backStack = params.get<MutableList<NavKey>>(),
                startDestination = params.get<NavRoutes>(),
            )
        }
        scoped { (onDrawerClick: () -> Unit) ->
            DrawerController(onDrawerClick)
        }
        scopedOf(::ProfilePickerController)

        navigation<NavRoutes.Configuration> { _ ->
            val drawerController = get<DrawerController>()
            val viewModel = koinViewModel<MainViewModel>()
            val navigator = get<Navigator>()
            ConfigurationScreen(
                mainViewModel = viewModel,
                onNavigationClick = drawerController::toggle,
                onOpenProfileEditor = navigator::navigateTo,
                onSwitchToSimpleMode = {
                    DataStore.simpleMode = true
                    navigator.navigateTo(NavRoutes.Simple)
                },
            )
        }

        navigation<NavRoutes.Simple> { _ ->
            val viewModel = koinViewModel<MainViewModel>()
            val navigator = get<Navigator>()
            SimpleHomeScreen(
                mainViewModel = viewModel,
                onOpenFullMode = {
                    navigator.navigateTo(NavRoutes.Configuration)
                },
            )
        }

        navigation<NavRoutes.Groups> { _ ->
            val drawerController = get<DrawerController>()
            val viewModel = koinViewModel<MainViewModel>()
            val navigator = get<Navigator>()
            GroupScreen(
                mainViewModel = viewModel,
                onDrawerClick = drawerController::toggle,
                openGroupSettings = { groupId ->
                    navigator.navigateTo(NavRoutes.GroupSettings(groupId = groupId))
                },
            )
        }

        navigation<NavRoutes.Route> { _ ->
            val drawerController = get<DrawerController>()
            val viewModel = koinViewModel<MainViewModel>()
            val navigator = get<Navigator>()
            RouteScreen(
                mainViewModel = viewModel,
                onDrawerClick = drawerController::toggle,
                openRouteSettings = { routeId ->
                    navigator.navigateTo(NavRoutes.RouteSettings(routeId = routeId))
                },
                openAssets = {
                    navigator.navigateTo(NavRoutes.Assets)
                },
            )
        }

        navigation<NavRoutes.Settings> { _ ->
            val drawerController = get<DrawerController>()
            val viewModel = koinViewModel<MainViewModel>()
            val navigator = get<Navigator>()
            SettingsScreen(
                mainViewModel = viewModel,
                onDrawerClick = drawerController::toggle,
                openAppManager = {
                    navigator.navigateTo(NavRoutes.AppManager)
                },
            )
        }

        navigation<NavRoutes.QuickSettings> { _ ->
            val drawerController = get<DrawerController>()
            val viewModel = koinViewModel<MainViewModel>()
            val navigator = get<Navigator>()
            QuickSettingsScreen(
                mainViewModel = viewModel,
                onDrawerClick = drawerController::toggle,
                openAppManager = { navigator.navigateTo(NavRoutes.AppManager) },
                onOpenAppUpdate = { navigator.navigateTo(NavRoutes.AppUpdate) },
            )
        }

        navigation<NavRoutes.Plugin> { _ ->
            val drawerController = get<DrawerController>()
            val viewModel = koinViewModel<MainViewModel>()
            PluginScreen(
                mainViewModel = viewModel,
                onDrawerClick = drawerController::toggle,
            )
        }

        navigation<NavRoutes.Log> { _ ->
            val drawerController = get<DrawerController>()
            val viewModel = koinViewModel<MainViewModel>()
            LogcatScreen(
                mainViewModel = viewModel,
                onDrawerClick = drawerController::toggle,
            )
        }

        navigation<NavRoutes.Dashboard> { _ ->
            val drawerController = get<DrawerController>()
            val viewModel = koinViewModel<MainViewModel>()
            val navigator = get<Navigator>()
            DashboardScreen(
                mainViewModel = viewModel,
                onDrawerClick = drawerController::toggle,
                openConnectionDetail = { uuid ->
                    navigator.navigateTo(NavRoutes.ConnectionsDetail(uuid = uuid))
                },
            )
        }

        navigation<NavRoutes.ConnectionsDetail> { route ->
            val navigator = get<Navigator>()
            ConnectionDetailScreen(
                uuid = route.uuid,
                popup = { navigator.popBackStack() },
                openRouteSettings = { initialState ->
                    navigator.navigateTo(
                        NavRoutes.RouteSettings(
                            routeId = -1L,
                            useDraft = true,
                            initialState = initialState,
                        ),
                    )
                },
            )
        }

        navigation<NavRoutes.ProfileEditor> { route ->
            val navigator = get<Navigator>()
            val profilePickerController = get<ProfilePickerController>()
            val resultBus = LocalResultEventBus.current
            ProfileEditorScreen(
                type = route.type,
                profileId = route.id,
                isSubscription = route.subscription,
                onOpenProfileSelect = profilePickerController::open,
                onOpenConfigEditor = navigator::navigateTo,
                onResult = { updated ->
                    resultBus.sendResult(route.resultKey, updated)
                    navigator.popBackStack()
                },
            )
        }

        navigation<NavRoutes.GroupSettings> { route ->
            val navigator = get<Navigator>()
            val profilePickerController = get<ProfilePickerController>()
            GroupSettingsScreen(
                groupId = route.groupId,
                onBackPress = { navigator.popBackStack() },
                onOpenProfileSelect = profilePickerController::open,
            )
        }

        navigation<NavRoutes.RouteSettings> { route ->
            val navigator = get<Navigator>()
            val profilePickerController = get<ProfilePickerController>()
            RouteSettingsScreen(
                routeId = route.routeId,
                initialState = route.initialState.takeIf { route.useDraft },
                onBackPress = { navigator.popBackStack() },
                onSaved = { navigator.popBackStack() },
                onOpenProfileSelect = profilePickerController::open,
                onOpenAppList = navigator::navigateTo,
                onOpenConfigEditor = navigator::navigateTo,
            )
        }

        navigation<NavRoutes.ConfigEditor> { route ->
            val navigator = get<Navigator>()
            ConfigEditScreen(
                initialText = route.initialText,
                resultKey = route.resultKey,
                onBack = { navigator.popBackStack() },
            )
        }

        navigation<NavRoutes.Assets> { _ ->
            val navigator = get<Navigator>()
            AssetsScreen(
                onBackPress = { navigator.popBackStack() },
                onOpenAssetEditor = navigator::navigateTo,
            )
        }

        navigation<NavRoutes.AssetEdit> { route ->
            val navigator = get<Navigator>()
            AssetEditScreen(
                assetName = route.assetName,
                resultKey = route.resultKey,
                onBack = { navigator.popBackStack() },
            )
        }

        navigation<NavRoutes.Tools> { _ ->
            val drawerController = get<DrawerController>()
            val viewModel = koinViewModel<MainViewModel>()
            val navigator = get<Navigator>()
            ToolsScreen(
                mainViewModel = viewModel,
                onDrawerClick = drawerController::toggle,
                onOpenTool = navigator::navigateTo,
            )
        }

        navigation<NavRoutes.ToolsPage.Stun> { _ ->
            val navigator = get<Navigator>()
            StunScreen(
                onBackPress = { navigator.popBackStack() },
            )
        }

        navigation<NavRoutes.ToolsPage.GetCert> { _ ->
            val navigator = get<Navigator>()
            GetCertScreen(
                onBack = { navigator.popBackStack() },
            )
        }

        navigation<NavRoutes.ToolsPage.SpeedTest> { _ ->
            val navigator = get<Navigator>()
            SpeedtestScreen(
                onBackPress = { navigator.popBackStack() },
            )
        }

        navigation<NavRoutes.ToolsPage.RuleSetMatch> { _ ->
            val navigator = get<Navigator>()
            RuleSetMatchScreen(
                onBackPress = { navigator.popBackStack() },
            )
        }

        navigation<NavRoutes.AppUpdate> { _ ->
            val drawerController = get<DrawerController>()
            AppUpdateScreen(
                onDrawerClick = drawerController::toggle,
            )
        }

        navigation<NavRoutes.About> { _ ->
            val drawerController = get<DrawerController>()
            val viewModel = koinViewModel<MainViewModel>()
            val navigator = get<Navigator>()
            AboutScreen(
                mainViewModel = viewModel,
                onDrawerClick = drawerController::toggle,
                onNavigateToLibraries = {
                    navigator.navigateTo(NavRoutes.Libraries)
                },
            )
        }

        navigation<NavRoutes.Libraries> { _ ->
            val navigator = get<Navigator>()
            LibrariesScreen(
                onBackPress = { navigator.popBackStack() },
            )
        }
    }
}
