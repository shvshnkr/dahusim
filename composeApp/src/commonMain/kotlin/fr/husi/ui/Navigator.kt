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

    private fun NavRoutes.isDrawerRoute(): Boolean {
        return when (this) {
            NavRoutes.Configuration,
            NavRoutes.Groups,
            NavRoutes.Route,
            NavRoutes.Settings,
            NavRoutes.Plugin,
            NavRoutes.Log,
            NavRoutes.Dashboard,
            NavRoutes.Tools,
            NavRoutes.About,
            NavRoutes.AppUpdate,
            NavRoutes.QuickSettings,
                -> true

            else -> false
        }
    }

    val isAtStartDestination: Boolean
        get() = currentRoute == startDestination

    fun popBackStack(): Boolean {
        if (backStack.size <= 1) {
            return false
        }
        backStack.removeLastOrNull()
        return true
    }

    fun navigateTo(route: NavRoutes) {
        backStack.add(route)
    }

    fun navigateToDrawerRoute(route: NavRoutes) {
        while (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
        if (backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }
}
