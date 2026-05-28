package fr.husi.simplemode

import fr.husi.database.Probe2kDefaults

/** Scheduling guards for [WarmReserveMaintainer] (unit-testable). */
internal object WarmReserveMaintainerPolicy {

    fun canSchedule(featureEnabled: Boolean, connectedProfileId: Long): Boolean =
        featureEnabled && connectedProfileId > 0L

    fun shouldSkipReplenish(
        reason: String,
        nowMs: Long,
        lastReplenishAtMs: Long,
        debounceMs: Long = Probe2kDefaults.WARM_RESERVE_REPLENISH_DEBOUNCE_MS,
    ): Boolean = reason != "pre_fallback" && nowMs - lastReplenishAtMs < debounceMs
}
