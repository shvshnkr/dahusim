package fr.husi.database

object Probe2kProgress {

    fun publishScan(checked: Int, total: Int) {
        DataStore.probe2kScanChecked = checked.coerceAtLeast(0)
        DataStore.probe2kScanTotal = total.coerceAtLeast(0)
        if (total > 0 && DataStore.simpleMode && !DataStore.serviceState.connected) {
            DataStore.simpleModeActivity = "Scanning servers $checked/$total…"
        }
    }

    fun clearScan() {
        DataStore.probe2kScanChecked = 0
        DataStore.probe2kScanTotal = 0
    }

    suspend fun refreshPoolCounts() {
        if (!DataStore.probe2kPersistenceEnabled) return
        DataStore.probe2kPoolAlive = SagerDatabase.probeStateDao.countByState(ProbeState.ALIVE)
        DataStore.probe2kPoolCandidate = SagerDatabase.probeStateDao.countByState(ProbeState.CANDIDATE)
        DataStore.probe2kPoolSuspect = SagerDatabase.probeStateDao.countByState(ProbeState.SUSPECT)
        DataStore.probe2kPoolDead = SagerDatabase.probeStateDao.countByState(ProbeState.DEAD)
        DataStore.probe2kPoolCemetery = SagerDatabase.probeStateDao.countByState(ProbeState.CEMETERY)
        val tracked = DataStore.probe2kPoolAlive + DataStore.probe2kPoolCandidate +
            DataStore.probe2kPoolSuspect + DataStore.probe2kPoolDead + DataStore.probe2kPoolCemetery
        val all = SagerDatabase.proxyDao.getAll().size
        DataStore.probe2kPoolUnknown = (all - tracked).coerceAtLeast(0)
    }

    fun hasPoolSummary(): Boolean {
        if (!DataStore.probe2kPersistenceEnabled) return false
        return DataStore.probe2kPoolAlive + DataStore.probe2kPoolCandidate +
            DataStore.probe2kPoolDead + DataStore.probe2kPoolCemetery + DataStore.probe2kPoolUnknown > 0
    }
}
