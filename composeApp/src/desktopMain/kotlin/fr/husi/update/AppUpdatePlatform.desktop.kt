package fr.husi.update

import fr.husi.ktx.onDefaultDispatcher
import fr.husi.platform.PlatformInfo
import fr.husi.database.DataStore
import java.awt.Desktop
import java.io.File
actual object AppUpdatePlatform {

    actual fun preferredAndroidAbi(): String? = null
    actual fun installedAndroidCertSha256(): String? = null

    actual fun canInstallPackages(): Boolean = true

    actual fun requestInstallPackagePermission() = Unit

    actual suspend fun installOffer(offer: AppUpdateOffer): AppUpdateInstallResult = onDefaultDispatcher {
        val desktopAsset = when {
            PlatformInfo.isLinux -> offer.linuxAsset?.let {
                DesktopAsset(
                    kind = it.kind,
                    binary = it.binary,
                )
            }
            PlatformInfo.isWindows -> offer.windowsAsset?.let {
                DesktopAsset(
                    kind = it.kind,
                    binary = it.binary,
                )
            }
            else -> null
        } ?: return@onDefaultDispatcher AppUpdateInstallResult.Failed("No desktop update artifact in manifest")

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "dahusim-app-update").apply { mkdirs() }
        val fileName = desktopAsset.binary.url.substringAfterLast('/').ifBlank { "update.bin" }
        val target = File(cacheDir, fileName)

        runCatching {
            AppUpdateDownload.download(desktopAsset.binary.url, target)
            if (desktopAsset.binary.size > 0L && target.length() != desktopAsset.binary.size) {
                error("Downloaded file size mismatch")
            }
            if (!sha256Matches(target, desktopAsset.binary.sha256)) {
                error("Downloaded file hash mismatch")
            }
            DataStore.appUpdateLastDownloadedPath = target.absolutePath
            if (!Desktop.isDesktopSupported()) {
                error("Desktop integration is not available")
            }
            val desktop = Desktop.getDesktop()
            if (PlatformInfo.isWindows && desktopAsset.kind != "installer") {
                // zip/jar requires manual extraction/run; opening folder is less error-prone than opening file.
                openFileOrParent(desktop, target, forceParent = true)
            } else {
                openFileOrParent(desktop, target, forceParent = false)
            }
        }.fold(
            onSuccess = { AppUpdateInstallResult.PendingUserAction },
            onFailure = { error ->
                target.delete()
                AppUpdateInstallResult.Failed(error.message ?: error.toString())
            },
        )
    }

    actual suspend fun reopenDownloadedArtifact(): AppUpdateInstallResult = onDefaultDispatcher {
        val path = DataStore.appUpdateLastDownloadedPath.trim()
        if (path.isBlank()) {
            return@onDefaultDispatcher AppUpdateInstallResult.Failed("No downloaded update file yet")
        }
        val file = File(path)
        if (!file.exists()) {
            return@onDefaultDispatcher AppUpdateInstallResult.Failed("Downloaded update file is missing")
        }
        runCatching {
            if (!Desktop.isDesktopSupported()) {
                error("Desktop integration is not available")
            }
            val desktop = Desktop.getDesktop()
            openFileOrParent(desktop, file, forceParent = PlatformInfo.isWindows)
        }.fold(
            onSuccess = { AppUpdateInstallResult.PendingUserAction },
            onFailure = { error ->
                AppUpdateInstallResult.Failed(error.message ?: error.toString())
            },
        )
    }

    private data class DesktopAsset(
        val kind: String,
        val binary: AppUpdateBinary,
    )

    private fun openFileOrParent(
        desktop: Desktop,
        file: File,
        forceParent: Boolean,
    ) {
        if (!desktop.isSupported(Desktop.Action.OPEN)) {
            error("Cannot open installer on this desktop")
        }
        val openTarget = if (forceParent) {
            file.parentFile ?: file
        } else {
            file
        }
        runCatching {
            desktop.open(openTarget)
        }.getOrElse {
            val fallback = file.parentFile
            if (fallback == null || fallback == openTarget) throw it
            desktop.open(fallback)
        }
    }
}
