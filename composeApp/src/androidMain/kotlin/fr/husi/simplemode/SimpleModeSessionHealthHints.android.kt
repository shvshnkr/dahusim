package fr.husi.simplemode

internal actual fun hasPendingSessionHealthDegradation(): Boolean =
    SimpleModeSessionHealth.hasPendingDegradation()
