package fr.husi.bootstrap

import fr.husi.database.DataStore
import fr.husi.repository.resolveAndroidRepository

internal actual suspend fun bootstrapPerAppDefaults() {
    if (DataStore.defaultPerAppBootstrapped) {
        ensureTelegramVariantsIncluded()
        ensureExtraTelegramAndYouTubeVariantsIncluded()
        return
    }

    val packageManager = resolveAndroidRepository().packageManager
    val packages = linkedSetOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        // Plus Messenger (popular alternative Telegram client package name)
        "org.telegram.plus",
        "org.thunderdog.challegram",
        "com.whatsapp",
        "com.google.android.youtube",
        // YouTube TV / Google TV package variants
        "com.google.android.youtube.tv",
        "com.google.android.youtube.googletv",
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

private fun ensureExtraTelegramAndYouTubeVariantsIncluded() {
    // Users may have installed "alternative" Telegram/YouTube clients.
    // If our stored per-app include-list doesn't contain their actual packageName,
    // apps look "connected but no traffic".
    val current = DataStore.packages
    if (current.isEmpty()) return
    val merged = current.toMutableSet().apply {
        add("org.telegram.plus")
        add("com.google.android.youtube.tv")
        add("com.google.android.youtube.googletv")
    }
    if (merged.size != current.size) {
        DataStore.packages = merged
    }
}
