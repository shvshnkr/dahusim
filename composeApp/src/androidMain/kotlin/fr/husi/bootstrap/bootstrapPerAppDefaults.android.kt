package fr.husi.bootstrap

import fr.husi.database.DataStore
import fr.husi.repository.resolveAndroidRepository

internal actual suspend fun bootstrapPerAppDefaults() {
    if (DataStore.defaultPerAppBootstrapped) {
        ensureTelegramVariantsIncluded()
        return
    }

    val packageManager = resolveAndroidRepository().packageManager
    val packages = linkedSetOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "com.whatsapp",
        "com.google.android.youtube",
    )
    listOf(
        "app.revanced.android.youtube",
        "app.rvx.android.youtube",
    ).firstOrNull { packageName ->
        runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }?.let { packages.add(it) }

    DataStore.proxyApps = true
    DataStore.bypassMode = false
    DataStore.packages = packages
    DataStore.defaultPerAppBootstrapped = true
}

private fun ensureTelegramVariantsIncluded() {
    val current = DataStore.packages
    if (current.isEmpty()) return
    val hasTelegram = current.any {
        it == "org.telegram.messenger" || it == "org.telegram.messenger.web"
    }
    if (!hasTelegram) return
    val merged = current.toMutableSet()
    merged.add("org.telegram.messenger")
    merged.add("org.telegram.messenger.web")
    if (merged.size != current.size) {
        DataStore.packages = merged
    }
}
