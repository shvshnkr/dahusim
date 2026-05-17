package fr.husi.simplemode

/** Wait until the UI can show the system VPN permission dialog (Android); always true on desktop. */
internal expect suspend fun awaitSimpleModeVpnPermissionUi(timeoutMs: Long = 30_000L): Boolean
