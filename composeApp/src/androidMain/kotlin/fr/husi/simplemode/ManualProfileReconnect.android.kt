package fr.husi.simplemode

import fr.husi.database.DataStore

actual fun prepareManualProfileReload() {
    SimpleModeTunnelRestart.markModeReconnect(DataStore.activeWhitelistRestrictedNetwork)
}
