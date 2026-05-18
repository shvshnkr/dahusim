package fr.husi.update

expect object AppUpdatePlatform {
    fun preferredAndroidAbi(): String?
    fun installedAndroidCertSha256(): String?
    /** Android: unknown-app install permission. Other platforms: always true. */
    fun canInstallPackages(): Boolean
    /** Android: opens system screen to allow APK installs. No-op elsewhere. */
    fun requestInstallPackagePermission()
    suspend fun installOffer(offer: AppUpdateOffer): AppUpdateInstallResult
    suspend fun reopenDownloadedArtifact(): AppUpdateInstallResult
}
