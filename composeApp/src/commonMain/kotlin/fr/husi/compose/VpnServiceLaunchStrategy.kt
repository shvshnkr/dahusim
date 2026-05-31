package fr.husi.compose

internal enum class VpnServiceLaunchResult {
    Started,
    RequestedPermission,
    NoActivity,
    Failed,
}

/** Pure launch-path selection for [VpnServiceLaunchRegistry] (unit-testable). */
internal object VpnServiceLaunchStrategy {
    enum class PrimaryPath {
        ComposeLauncher,
        ActivityFallback,
    }

    fun canLaunch(isLauncherRegistered: Boolean, hasActivity: Boolean): Boolean =
        isLauncherRegistered || hasActivity

    fun choosePrimaryPath(isLauncherRegistered: Boolean): PrimaryPath =
        if (isLauncherRegistered) PrimaryPath.ComposeLauncher else PrimaryPath.ActivityFallback
}
