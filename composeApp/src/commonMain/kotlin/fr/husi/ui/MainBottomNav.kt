package fr.husi.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.husi.compose.material3.Icon
import fr.husi.compose.material3.Text
import fr.husi.resources.Res
import fr.husi.resources.directions
import fr.husi.resources.home
import fr.husi.resources.menu_library
import fr.husi.resources.menu_more
import fr.husi.resources.menu_route
import fr.husi.resources.more_vert
import fr.husi.resources.simple_mode_tab
import fr.husi.resources.view_list
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@Composable
internal fun MainBottomNavigationBar(
    selectedTab: NavRoutes,
    onTabSelected: (NavRoutes) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        BottomNavTab(NavRoutes.Library, Res.string.menu_library, Res.drawable.view_list),
        BottomNavTab(NavRoutes.Route, Res.string.menu_route, Res.drawable.directions),
        BottomNavTab(NavRoutes.More, Res.string.menu_more, Res.drawable.more_vert),
        BottomNavTab(NavRoutes.Simple, Res.string.simple_mode_tab, Res.drawable.home),
    )
    NavigationBar(
        modifier = modifier,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab.route,
                onClick = { onTabSelected(tab.route) },
                icon = {
                    Icon(
                        imageVector = vectorResource(tab.icon),
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}

private data class BottomNavTab(
    val route: NavRoutes,
    val label: StringResource,
    val icon: DrawableResource,
)
