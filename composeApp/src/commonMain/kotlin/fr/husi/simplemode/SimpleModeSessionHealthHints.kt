package fr.husi.simplemode

/** Whether the user is leaving simple mode while tunnel health recovery is still pending. */
internal expect fun hasPendingSessionHealthDegradation(): Boolean
