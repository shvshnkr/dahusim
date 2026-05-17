package fr.husi.simplemode

internal actual suspend fun awaitSimpleModeVpnPermissionUi(timeoutMs: Long): Boolean {
    timeoutMs // desktop has no VpnService permission gate
    return true
}
