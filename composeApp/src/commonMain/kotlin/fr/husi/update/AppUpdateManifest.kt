package fr.husi.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppUpdateManifest(
    val schema: Int = 1,
    val publishedAt: String? = null,
    val offerUpdate: Boolean = false,
    val mandatory: Boolean = false,
    val minVersionCode: Int = 0,
    val versionName: String = "",
    val versionCode: Int = 0,
    val sourceCommit: String? = null,
    val sourceRunId: String? = null,
    val notes: String = "",
    val signing: AppUpdateSigning? = null,
    val android: AppUpdateAndroid? = null,
    val linux: AppUpdateLinux? = null,
    val windows: AppUpdateWindows? = null,
)

@Serializable
data class AppUpdateSigning(
    val androidCertSha256: List<String> = emptyList(),
)

@Serializable
data class AppUpdateAndroid(
    val flavor: String = "play",
    val buildType: String = "debug",
    val abis: Map<String, AppUpdateBinary> = emptyMap(),
)

@Serializable
data class AppUpdateLinux(
    val arch: String = "amd64",
    val assets: Map<String, AppUpdateBinary> = emptyMap(),
)

@Serializable
data class AppUpdateWindows(
    val arch: String = "amd64",
    val installer: AppUpdateBinary? = null,
    val zip: AppUpdateBinary? = null,
    val jar: AppUpdateBinary? = null,
)

@Serializable
data class AppUpdateBinary(
    val url: String,
    val sha256: String,
    val size: Long = 0L,
)

@Serializable
data class GithubReleaseAssetResponse(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

data class AppUpdateOffer(
    val manifest: AppUpdateManifest,
    val versionName: String,
    val versionCode: Int,
    val mandatory: Boolean,
    val notes: String,
    val androidApk: AppUpdateBinary?,
    val linuxAsset: AppUpdateLinuxAsset?,
    val windowsAsset: AppUpdateWindowsAsset?,
)

data class AppUpdateLinuxAsset(
    val kind: String,
    val binary: AppUpdateBinary,
)

data class AppUpdateWindowsAsset(
    val kind: String,
    val binary: AppUpdateBinary,
)

sealed class AppUpdateCheckResult {
    data object Disabled : AppUpdateCheckResult()
    data object UpToDate : AppUpdateCheckResult()
    data object NoPlatformArtifact : AppUpdateCheckResult()
    data class Error(val message: String) : AppUpdateCheckResult()
    data class Available(val offer: AppUpdateOffer) : AppUpdateCheckResult()
}

sealed class AppUpdateInstallResult {
    data object Success : AppUpdateInstallResult()
    data object PendingUserAction : AppUpdateInstallResult()
    data object Cancelled : AppUpdateInstallResult()
    data class Failed(val message: String) : AppUpdateInstallResult()
}

enum class AppUpdateInstallStage {
    IDLE,
    PREPARING,
    DOWNLOADING,
    VERIFYING,
    LAUNCHING_INSTALLER,
    AWAITING_USER_ACTION,
}

data class AppUpdateInstallState(
    val active: Boolean = false,
    val stage: AppUpdateInstallStage = AppUpdateInstallStage.IDLE,
    val errorMessage: String? = null,
)
