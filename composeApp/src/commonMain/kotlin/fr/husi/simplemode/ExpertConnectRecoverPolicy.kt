package fr.husi.simplemode

import fr.husi.database.DataStore

object ExpertConnectRecoverPolicy {

    fun allowsFullModeSessionFallback(simpleMode: Boolean, expertRecoverEnabled: Boolean): Boolean =
        simpleMode || expertRecoverEnabled

    fun allowsFullModeHealthRecover(simpleMode: Boolean, expertRecoverEnabled: Boolean): Boolean =
        simpleMode || expertRecoverEnabled

    /** Session fallback queue (H17 probe fail, post-connect switch) when not in simple mode. */
    fun allowsFullModeSessionFallback(): Boolean =
        allowsFullModeSessionFallback(DataStore.simpleMode, DataStore.expertConnectRecoverEnabled)

    fun allowsFullModeHealthRecover(): Boolean =
        allowsFullModeHealthRecover(DataStore.simpleMode, DataStore.expertConnectRecoverEnabled)
}
