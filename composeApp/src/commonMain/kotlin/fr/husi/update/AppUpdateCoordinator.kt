package fr.husi.update

import fr.husi.bg.currentEpochSeconds
import fr.husi.database.DataStore
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppUpdateCoordinator {

    private val repository = AppUpdateRepository()
    private val _pendingOffer = MutableStateFlow<AppUpdateOffer?>(null)
    val pendingOffer: StateFlow<AppUpdateOffer?> = _pendingOffer.asStateFlow()

    suspend fun checkForUpdate(manual: Boolean = false): AppUpdateCheckResult = onDefaultDispatcher {
        if (!DataStore.appUpdateCheckEnabled && !manual) {
            return@onDefaultDispatcher AppUpdateCheckResult.Disabled
        }

        val now = currentEpochSeconds()
        if (!manual && !AppUpdateAutoUpdatePlanner.isCheckDue(now)) {
            return@onDefaultDispatcher AppUpdateCheckResult.UpToDate
        }

        val result = runCatching {
            val manifest = repository.fetchManifest()
            AppUpdateEvaluator.evaluate(manifest)
        }.getOrElse { error ->
            simpleModeLog(
                "SimpleMode",
                "H36 app_update_check_fail error=${error.message ?: error.javaClass.simpleName}",
            )
            return@onDefaultDispatcher AppUpdateCheckResult.Error(error.message ?: error.toString())
        }

        DataStore.appUpdateLastCheckAt = now

        when (result) {
            is AppUpdateCheckResult.Available -> {
                if (!manual && isDismissed(result.offer)) {
                    AppUpdateCheckResult.UpToDate
                } else {
                    _pendingOffer.value = result.offer
                    result
                }
            }
            else -> result
        }
    }

    fun dismissOffer(offer: AppUpdateOffer) {
        if (!offer.mandatory) {
            DataStore.appUpdateDismissedVersionCode = offer.versionCode
        }
        _pendingOffer.value = null
    }

    fun clearPendingOffer() {
        _pendingOffer.value = null
    }

    fun disableChecks() {
        DataStore.appUpdateCheckEnabled = false
        _pendingOffer.value = null
    }

    suspend fun installPendingOffer(): AppUpdateInstallResult {
        val offer = _pendingOffer.value ?: return AppUpdateInstallResult.Cancelled
        val result = AppUpdatePlatform.installOffer(offer)
        if (result is AppUpdateInstallResult.Success || result is AppUpdateInstallResult.PendingUserAction) {
            _pendingOffer.value = null
        }
        return result
    }

    suspend fun reopenDownloadedArtifact(): AppUpdateInstallResult {
        return AppUpdatePlatform.reopenDownloadedArtifact()
    }

    private fun isDismissed(offer: AppUpdateOffer): Boolean {
        if (offer.mandatory) return false
        return DataStore.appUpdateDismissedVersionCode == offer.versionCode
    }
}
