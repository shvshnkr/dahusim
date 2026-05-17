package fr.husi.update

import fr.husi.ktx.onDefaultDispatcher
import fr.husi.platform.PlatformInfo
import java.awt.Desktop
import java.io.File
actual object AppUpdatePlatform {

    actual fun preferredAndroidAbi(): String? = null
    actual fun installedAndroidCertSha256(): String? = null

    actual suspend fun installOffer(offer: AppUpdateOffer): AppUpdateInstallResult = onDefaultDispatcher {
        val asset = when {
            PlatformInfo.isLinux -> offer.linuxAsset
            PlatformInfo.isWindows -> offer.windowsAsset
            else -> null
        } ?: return@onDefaultDispatcher AppUpdateInstallResult.Failed("No desktop update artifact in manifest")

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "dahusim-app-update").apply { mkdirs() }
        val fileName = asset.binary.url.substringAfterLast('/').ifBlank { "update.bin" }
        val target = File(cacheDir, fileName)

        runCatching {
            AppUpdateDownload.download(asset.binary.url, target)
            if (!sha256Matches(target, asset.binary.sha256)) {
                error("Downloaded file hash mismatch")
            }
            if (!Desktop.isDesktopSupported()) {
                error("Desktop integration is not available")
            }
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                error("Cannot open installer on this desktop")
            }
            desktop.open(target)
        }.fold(
            onSuccess = { AppUpdateInstallResult.Success },
            onFailure = { error ->
                target.delete()
                AppUpdateInstallResult.Failed(error.message ?: error.toString())
            },
        )
    }
}
