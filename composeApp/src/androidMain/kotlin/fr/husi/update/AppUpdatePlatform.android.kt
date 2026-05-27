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
import fr.husi.repository.Repository
import fr.husi.repository.resolveAndroidRepository
import fr.husi.repository.resolveRepository
import fr.husi.resources.Res
import fr.husi.resources.app_update_apk_cert_untrusted
import fr.husi.resources.app_update_apk_hash_mismatch
import fr.husi.resources.app_update_apk_sig_mismatch
import fr.husi.resources.app_update_cannot_read_apk_cert
import fr.husi.resources.app_update_cannot_read_installed_cert
import fr.husi.resources.app_update_install_permission_required
import fr.husi.resources.app_update_installer_timeout
import fr.husi.resources.app_update_no_apk
import fr.husi.utils.simpleModeLog
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.StringResource

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

    actual fun canInstallPackages(): Boolean = canRequestPackageInstalls()

    actual fun requestInstallPackagePermission() {
        openInstallPermissionSettings()
    }

    actual suspend fun installOffer(
        offer: AppUpdateOffer,
        onStageChanged: (AppUpdateInstallStage) -> Unit,
    ): AppUpdateInstallResult = onDefaultDispatcher {
        val repo = resolveRepository()
        val apkInfo = offer.androidApk
            ?: return@onDefaultDispatcher failed(repo, Res.string.app_update_no_apk)

        if (!canRequestPackageInstalls()) {
            openInstallPermissionSettings()
            return@onDefaultDispatcher failed(repo, Res.string.app_update_install_permission_required)
        }

        val cacheDir = File(appContext.cacheDir, "app-update").apply { mkdirs() }
        val apkFile = File(cacheDir, "update-${offer.versionCode}.apk")

        runCatching {
            onStageChanged(AppUpdateInstallStage.PREPARING)
            simpleModeLog(
                "SimpleMode",
                "H36 app_update_install_start version=${offer.versionCode} urlHost=${apkHost(apkInfo.url)}",
            )
            onStageChanged(AppUpdateInstallStage.DOWNLOADING)
            AppUpdateDownload.download(apkInfo.url, apkFile)
            onStageChanged(AppUpdateInstallStage.VERIFYING)
            if (!sha256Matches(apkFile, apkInfo.sha256)) {
                throw IllegalStateException(repo.getString(Res.string.app_update_apk_hash_mismatch))
            }
            verifySigning(repo, offer, apkFile)
            onStageChanged(AppUpdateInstallStage.LAUNCHING_INSTALLER)
            installApkAndAwait(repo, apkFile)
        }.fold(
            onSuccess = { result ->
                simpleModeLog("SimpleMode", "H36 app_update_install_done result=${result::class.simpleName}")
                result
            },
            onFailure = { error ->
                apkFile.delete()
                simpleModeLog(
                    "SimpleMode",
                    "H36 app_update_install_fail error=${error.message ?: error.javaClass.simpleName}",
                )
                AppUpdateInstallResult.Failed(error.message ?: error.toString())
            },
        )
    }

    actual suspend fun reopenDownloadedArtifact(): AppUpdateInstallResult {
        return AppUpdateInstallResult.Failed("Not supported on Android")
    }

    private suspend fun failed(repo: Repository, message: StringResource): AppUpdateInstallResult.Failed =
        AppUpdateInstallResult.Failed(repo.getString(message))

    private fun apkHost(url: String): String = runCatching {
        java.net.URL(url).host
    }.getOrDefault("unknown")

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

    private suspend fun verifySigning(repo: Repository, offer: AppUpdateOffer, apkFile: File) {
        val pm = appContext.packageManager
        val apkCert = readApkCertSha256(pm, apkFile)
            ?: throw IllegalStateException(repo.getString(Res.string.app_update_cannot_read_apk_cert))

        val allowed = offer.manifest.signing?.androidCertSha256.orEmpty()
            .map(::normalizeCertSha256)
            .filter { it.isNotEmpty() }
        if (allowed.isNotEmpty() && apkCert !in allowed) {
            throw IllegalStateException(repo.getString(Res.string.app_update_apk_cert_untrusted))
        }

        val installedCert = readInstalledCertSha256(pm)
            ?: throw IllegalStateException(repo.getString(Res.string.app_update_cannot_read_installed_cert))
        if (apkCert != installedCert) {
            throw IllegalStateException(repo.getString(Res.string.app_update_apk_sig_mismatch))
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

    private suspend fun installApkAndAwait(repo: Repository, apkFile: File): AppUpdateInstallResult {
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
            ?: failed(repo, Res.string.app_update_installer_timeout)
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
