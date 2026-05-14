package fr.husi.utils

import fr.husi.database.DataStore
import kotlinx.coroutines.sync.withLock

actual suspend fun enableRussianPerAppBypass(): Int {
    val russian = PackageCache.loaded.withLock {
        PackageCache.installedPackages.keys
            .asSequence()
            .filter { AppScanner.isRussianApp(it, PackageCache.packageManager) }
            .toCollection(LinkedHashSet())
    }
    if (russian.isEmpty()) return 0
    DataStore.proxyApps = true
    DataStore.bypassMode = true
    val merged = LinkedHashSet<String>(DataStore.packages.size + russian.size)
    merged.addAll(DataStore.packages)
    merged.addAll(russian)
    DataStore.packages = merged
    return russian.size
}
