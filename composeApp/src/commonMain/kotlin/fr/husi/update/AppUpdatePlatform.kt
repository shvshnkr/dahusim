package fr.husi.update

expect object AppUpdatePlatform {
    fun preferredAndroidAbi(): String?
    fun installedAndroidCertSha256(): String?
    suspend fun installOffer(offer: AppUpdateOffer): AppUpdateInstallResult
}
