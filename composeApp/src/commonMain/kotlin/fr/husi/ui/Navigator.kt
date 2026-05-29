package fr.husi.ui

import androidx.navigation3.runtime.NavKey

class Navigator(
    private val backStack: MutableList<NavKey>,
    private val startDestination: NavRoutes,
) {
    val currentRoute: NavRoutes?
        get() = backStack.lastOrNull() as? NavRoutes

    val selectedDrawerRoute: NavRoutes?
        get() = backStack.lastOrNull {
            (it as? NavRoutes)?.isDrawerRoute() == true
        } as? NavRoutes

    val selectedBottomNavTab: NavRoutes?
        get() = backStack.lastOrNull {
            (it as? NavRoutes)?.bottomNavTab() != null
        }
            ?.let { (it as NavRoutes).bottomNavTab() }

    private fun NavRoutes.isDrawerRoute(): Boolean {
        return when (this) {
            NavRoutes.Configuration,
            NavRoutes.Library,
            NavRoutes.Route,
            NavRoutes.More,
            NavRoutes.Settings,
            NavRoutes.Plugin,
            NavRoutes.Log,
            NavRoutes.Dashboard,
            NavRoutes.Tools,
            NavRoutes.About,
            NavRoutes.AppUpdate,
            NavRoutes.QuickSettings -> true

            is NavRoutes.LibraryGroup -> false
            else -> false
        }
    }

    val isAtStartDestination: Boolean
        get() = currentRoute == startDestination

    val showsBackNavigation: Boolean
        get() = backStack.size > 1

    fun popBackStack(): Boolean {
        if (backStack.size <= 1) {
            return false
        }
        backStack.removeLastOrNull()
        return true
    }

    fun navigateTo(route: NavRoutes) {
        val target = route.resolveDeprecatedNavTarget()
        backStack.add(target)
    }

    fun navigateToDrawerRoute(route: NavRoutes) {
        val target = route.resolveDeprecatedNavTarget()
        while (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
        if (backStack.lastOrNull() != target) {
            backStack.add(target)
        }
    }

    fun navigateUp(fallback: () -> Unit) {
        if (!popBackStack()) {
            fallback()
        }
    }
}

/** Maps legacy routes to Library Hub destinations. */
internal fun NavRoutes.resolveDeprecatedNavTarget(): NavRoutes = when (this) {
    NavRoutes.Groups -> NavRoutes.Library
    NavRoutes.QuickSettings -> NavRoutes.DahusimHub
    else -> this
}

internal fun NavRoutes.bottomNavTab(): NavRoutes? = when (this) {
    NavRoutes.Library,
    is NavRoutes.LibraryGroup,
    NavRoutes.Configuration,
    NavRoutes.Groups,
    -> NavRoutes.Library

    NavRoutes.Route -> NavRoutes.Route

    NavRoutes.More,
    NavRoutes.Settings,
    NavRoutes.QuickSettings,
    NavRoutes.DahusimHub,
    NavRoutes.DahusimNetwork,
    NavRoutes.DahusimSubscriptions,
    NavRoutes.DahusimAutoselect,
    NavRoutes.DahusimDiagnostics,
    NavRoutes.Plugin,
    NavRoutes.Log,
    NavRoutes.Dashboard,
    NavRoutes.Tools,
    NavRoutes.About,
    NavRoutes.AppUpdate,
    -> NavRoutes.More

    else -> null
}

internal fun NavRoutes.showsMainBottomNav(): Boolean = bottomNavTab() != null
