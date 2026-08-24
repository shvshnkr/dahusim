package fr.husi.scenario.journey

import fr.husi.bg.ServiceState
import fr.husi.database.DataStore
import fr.husi.ui.simple.SimpleTrailStepState
import fr.husi.ui.simple.StatusTone
import fr.husi.ui.simple.fallbackAttempt
import fr.husi.ui.simple.simpleTrailSteps
import fr.husi.ui.simple.statusTone
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The simple screen must keep problem states visible (approved mockup v2, 2026-08-24):
 * - FAILED is a persistent terminal tone for the network gate / all-probes-dead instead of a
 *   silent reset to Stopped (field 2026-08-21: user tapped Connect on BS, everything dead,
 *   UI read as "Connect is broken");
 * - RECOVERING covers problem recovery activities with the «Attempt N of M» pill sourced from
 *   the persisted fallback queue (AutoServerSelector.commitFallbackSelection), while clean
 *   bring-up texts stay CONNECTING;
 * - the step trail is always shown (Connected → all green, failure stage red).
 */
class SimpleScreenStateTonesJourneyTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        super.postStartKoin()
        DataStore.configurationStore.reset()
        DataStore.firstLaunchSubscriptionUiRefreshDone = true
    }

    @Test
    fun noInternetWhileStoppedRendersFailedToneWithNetworkStageFailed() = runBlocking {
        assertEquals(
            StatusTone.FAILED,
            statusTone(
                ServiceState.Stopped,
                permissionPending = false,
                activityText = "",
                noInternet = true,
            ),
        )
        val states = simpleTrailSteps(StatusTone.FAILED, "", noInternet = true, allServersDead = false)
            .map { it.second }
        assertEquals(
            listOf(
                SimpleTrailStepState.FAIL,
                SimpleTrailStepState.PENDING,
                SimpleTrailStepState.PENDING,
                SimpleTrailStepState.PENDING,
            ),
            states,
        )
    }

    @Test
    fun allServersDeadWhileIdleRendersFailedToneWithServerStageFailed() = runBlocking {
        assertEquals(
            StatusTone.FAILED,
            statusTone(
                ServiceState.Idle,
                permissionPending = false,
                activityText = "",
                allServersDead = true,
            ),
        )
        val states = simpleTrailSteps(StatusTone.FAILED, "", noInternet = false, allServersDead = true)
            .map { it.second }
        assertEquals(
            listOf(
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.FAIL,
                SimpleTrailStepState.PENDING,
            ),
            states,
        )
    }

    @Test
    fun fallbackQueuePersistedInDataStoreDrivesAttemptPill() = runBlocking {
        // What AutoServerSelector.commitFallbackSelection leaves behind after the first hop:
        // queue = prepare-time ranking, index = position of the candidate being tried.
        DataStore.autoSelectFallbackQueue = "11,22,33"
        DataStore.autoSelectFallbackIndex = 1

        assertEquals(2 to 3, fallbackAttempt(DataStore.autoSelectFallbackIndex, DataStore.autoSelectFallbackQueue))
        assertEquals(
            StatusTone.RECOVERING,
            statusTone(
                ServiceState.Connected,
                permissionPending = false,
                activityText = "Server unstable, switching...",
            ),
        )
        // The connected trail is fully green while the recovery headline is «Переключение…».
        val trail = simpleTrailSteps(StatusTone.RECOVERING, "Server unstable, switching...", false, false)
            .map { it.second }
        assertEquals(SimpleTrailStepState.CURRENT, trail.last())
    }

    @Test
    fun cleanBringUpTextsStayConnectingWhileRecoveryIsRecovering() = runBlocking {
        assertEquals(
            StatusTone.CONNECTING,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = "Connecting to server…"),
        )
        assertEquals(
            StatusTone.CONNECTING,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = "Starting VPN…"),
        )
        assertEquals(
            StatusTone.RECOVERING,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = "Connection unstable, rechecking…"),
        )
        assertEquals(
            StatusTone.RECOVERING,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = "Network changed, reconnecting…"),
        )
    }
}
