package fr.husi.update

import fr.husi.BuildConfig
import fr.husi.platform.PlatformInfo

object AppUpdateEvaluator {

    fun evaluate(
        manifest: AppUpdateManifest,
        installedVersionCode: Int = BuildConfig.VERSION_CODE,
    ): AppUpdateCheckResult {
        if (!manifest.offerUpdate) return AppUpdateCheckResult.UpToDate
        if (installedVersionCode < manifest.minVersionCode) return AppUpdateCheckResult.UpToDate
        if (manifest.versionCode <= installedVersionCode) return AppUpdateCheckResult.UpToDate

        val androidApk = if (PlatformInfo.isAndroid) {
            val candidate = pickAndroidApk(manifest)
            if (candidate != null && !isAndroidCertCompatible(manifest)) {
                null
            } else {
                candidate
            }
        } else {
            null
        }
        val linuxAsset = if (PlatformInfo.isLinux) pickLinuxAsset(manifest) else null
        val windowsAsset = if (PlatformInfo.isWindows) pickWindowsAsset(manifest) else null

        if (androidApk == null && linuxAsset == null && windowsAsset == null) {
            return AppUpdateCheckResult.NoPlatformArtifact
        }

        return AppUpdateCheckResult.Available(
            AppUpdateOffer(
                manifest = manifest,
                versionName = manifest.versionName,
                versionCode = manifest.versionCode,
                mandatory = manifest.mandatory,
                notes = manifest.notes.trim(),
                androidApk = androidApk,
                linuxAsset = linuxAsset,
                windowsAsset = windowsAsset,
            ),
        )
    }

    internal fun pickAndroidApk(manifest: AppUpdateManifest): AppUpdateBinary? {
        val abis = manifest.android?.abis ?: return null
        val preferred = AppUpdatePlatform.preferredAndroidAbi()
        val order = buildList {
            if (preferred != null) add(preferred)
            addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
        }.distinct()
        for (abi in order) {
            abis[abi]?.let { return it }
        }
        return abis.values.firstOrNull()
    }

    internal fun pickLinuxAsset(manifest: AppUpdateManifest): AppUpdateLinuxAsset? {
        val assets = manifest.linux?.assets ?: return null
        val order = listOf("deb", "rpm", "pkgTarZst", "zip", "jar")
        for (key in order) {
            assets[key]?.let { return AppUpdateLinuxAsset(key, it) }
        }
        return null
    }

    internal fun pickWindowsAsset(manifest: AppUpdateManifest): AppUpdateWindowsAsset? {
        val windows = manifest.windows ?: return null
        windows.installer?.let { return AppUpdateWindowsAsset("installer", it) }
        windows.zip?.let { return AppUpdateWindowsAsset("zip", it) }
        windows.jar?.let { return AppUpdateWindowsAsset("jar", it) }
        return null
    }

    private fun isAndroidCertCompatible(manifest: AppUpdateManifest): Boolean {
        val allowed = manifest.signing?.androidCertSha256.orEmpty()
            .map(::normalizeCert)
            .filter { it.isNotEmpty() }
        if (allowed.isEmpty()) return true
        val installed = AppUpdatePlatform.installedAndroidCertSha256()?.let(::normalizeCert) ?: return true
        return installed in allowed
    }

    private fun normalizeCert(value: String): String {
        return value.trim().uppercase()
            .replace(Regex("[^0-9A-F]"), "")
            .chunked(2)
            .joinToString(":")
    }
}
