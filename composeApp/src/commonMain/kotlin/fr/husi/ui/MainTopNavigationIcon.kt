package fr.husi.ui

import androidx.compose.runtime.Composable
import fr.husi.compose.PlatformMenuIcon
import fr.husi.compose.SimpleIconButton
import fr.husi.platform.PlatformInfo
import fr.husi.resources.Res
import fr.husi.resources.arrow_back
import fr.husi.resources.back
import fr.husi.resources.menu
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** Drawer/menu on desktop and drawer roots; back arrow on stacked screens (incl. Android bottom nav). */
@Composable
fun MainTopNavigationIcon(
    useBack: Boolean,
    onClick: () -> Unit,
    hideOnAndroidBottomNavRoot: Boolean = false,
) {
    if (!useBack && hideOnAndroidBottomNavRoot && PlatformInfo.isAndroid) return
    if (useBack) {
        SimpleIconButton(
            imageVector = vectorResource(Res.drawable.arrow_back),
            contentDescription = stringResource(Res.string.back),
            onClick = onClick,
        )
    } else {
        PlatformMenuIcon(
            imageVector = vectorResource(Res.drawable.menu),
            contentDescription = stringResource(Res.string.menu),
            onClick = onClick,
        )
    }
}

internal fun NavRoutes.isMainBottomNavRoot(): Boolean = when (this) {
    NavRoutes.Library,
    NavRoutes.Route,
    NavRoutes.More,
    -> true

    else -> false
}
