package fr.husi.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.repository.resolveAndroidRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

actual object AppUpdatePlatform {

    private val appContext: Context
        get() = resolveAndroidRepository().context

    actual fun preferredAndroidAbi(): String? {
        val supported = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val order = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        return order.firstOrNull { supported.contains(it) }
    }

    actual fun installedAndroidCertSha256(): String? {
        return readInstalledCertSha256(appContext.packageManager)
    }

    actual suspend fun installOffer(offer: AppUpdateOffer): AppUpdateInstallResult = onDefaultDispatcher {
        val apkInfo = offer.androidApk
            ?: return@onDefaultDispatcher AppUpdateInstallResult.Failed("No Android APK in update manifest")

        if (!canRequestPackageInstalls()) {
            openInstallPermissionSettings()
            return@onDefaultDispatcher AppUpdateInstallResult.Failed(
                "Allow installing updates for this app, then try again",
            )
        }

        val cacheDir = File(appContext.cacheDir, "app-update").apply { mkdirs() }
        val apkFile = File(cacheDir, "update-${offer.versionCode}.apk")

        runCatching {
            AppUpdateDownload.download(apkInfo.url, apkFile)
            if (!sha256Matches(apkFile, apkInfo.sha256)) {
                error("Downloaded APK hash mismatch")
            }
            verifySigning(offer, apkFile)
            installApkAndAwait(apkFile)
        }.fold(
            onSuccess = { result -> result },
            onFailure = { error ->
                apkFile.delete()
                AppUpdateInstallResult.Failed(error.message ?: error.toString())
            },
        )
    }

    actual suspend fun reopenDownloadedArtifact(): AppUpdateInstallResult {
        return AppUpdateInstallResult.Failed("Not supported on Android")
    }

    private fun canRequestPackageInstalls(): Boolean {
        return appContext.packageManager.canRequestPackageInstalls()
    }

    private fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    private fun verifySigning(offer: AppUpdateOffer, apkFile: File) {
        val pm = appContext.packageManager
        val apkCert = readApkCertSha256(pm, apkFile)
            ?: error("Cannot read APK signing certificate")

        val allowed = offer.manifest.signing?.androidCertSha256.orEmpty()
            .map(::normalizeCertSha256)
            .filter { it.isNotEmpty() }
        if (allowed.isNotEmpty() && apkCert !in allowed) {
            error("APK certificate is not trusted")
        }

        val installedCert = readInstalledCertSha256(pm)
            ?: error("Cannot read installed app certificate")
        if (apkCert != installedCert) {
            error("APK signature does not match installed app")
        }
    }

    private fun readInstalledCertSha256(pm: PackageManager): String? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = pm.getPackageInfo(appContext.packageName, flags)
        return readCertSha256(info)
    }

    private fun readApkCertSha256(pm: PackageManager, apkFile: File): String? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return null
        return readCertSha256(info)
    }

    private fun readCertSha256(info: PackageInfo): String? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return null
        val signature = signatures.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        return digest.joinToString(":") { byte -> "%02X".format(byte) }
    }

    private fun normalizeCertSha256(value: String): String {
        return value.trim().uppercase()
            .replace(Regex("[^0-9A-F]"), "")
            .chunked(2)
            .joinToString(":")
    }

    private suspend fun installApkAndAwait(apkFile: File): AppUpdateInstallResult {
        val pm = appContext.packageManager
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appContext.packageName)
        }
        val sessionId = installer.createSession(params)
        val deferred = AppUpdateInstallAwaiter.register(sessionId)
        val session = installer.openSession(sessionId)
        apkFile.inputStream().use { input ->
            session.openWrite("app-update", 0, apkFile.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }
        val intent = Intent(appContext, AppUpdateInstallReceiver::class.java).apply {
            putExtra(AppUpdateInstallReceiver.EXTRA_SESSION_ID, sessionId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            sessionId,
            intent,
            flags,
        )
        session.commit(pendingIntent.intentSender)
        session.close()
        return withTimeoutOrNull(120_000L) { deferred.await() }
            ?: AppUpdateInstallResult.Failed("Installer callback timeout")
    }
}

internal object AppUpdateInstallAwaiter {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<AppUpdateInstallResult>>()

    fun register(sessionId: Int): CompletableDeferred<AppUpdateInstallResult> {
        val deferred = CompletableDeferred<AppUpdateInstallResult>()
        pending[sessionId] = deferred
        return deferred
    }

    fun complete(sessionId: Int, result: AppUpdateInstallResult) {
        val deferred = pending.remove(sessionId) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(result)
        }
    }
}
