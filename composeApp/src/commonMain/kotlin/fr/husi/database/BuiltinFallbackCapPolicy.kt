package fr.husi.database

import fr.husi.utils.simpleModeLog

/**
 * Limits built-in standalone share in autoselect fallback queue (2K preset).
 */
internal object BuiltinFallbackCapPolicy {

    fun shouldApply(userMode: UserPoolMode): Boolean =
        DataStore.probe2kBuiltinFallbackCapEnabled && appliesForUserPoolMode(userMode)

    fun appliesForUserPoolMode(userMode: UserPoolMode): Boolean = when (userMode) {
        UserPoolMode.EXCLUSIVE -> false
        UserPoolMode.PRIORITY_FALLBACK -> UserPoolPolicy.simpleModeUserPoolFallbackUsed
        else -> true
    }

    fun isBuiltinProfile(profile: ProxyEntity?): Boolean =
        profile != null && UserSubscriptionTag.isBuiltinStandaloneProfile(profile)

    fun applyCap(
        rankedIds: List<Long>,
        profilesById: Map<Long, ProxyEntity>,
        maxFraction: Double = Probe2kDefaults.BUILTIN_FALLBACK_MAX_FRACTION,
    ): List<Long> {
        val isBuiltin: (Long) -> Boolean = { isBuiltinProfile(profilesById[it]) }
        val capped = capRankedIds(rankedIds, isBuiltin, maxFraction)
        if (capped != rankedIds) {
            simpleModeLog(
                "SimpleMode",
                "H39 builtin_fallback_cap queue=${rankedIds.size}",
            )
        }
        return capped
    }

    internal fun capRankedIds(
        rankedIds: List<Long>,
        isBuiltin: (Long) -> Boolean,
        maxFraction: Double,
    ): List<Long> {
        if (rankedIds.isEmpty()) return rankedIds
        val maxBuiltin = (rankedIds.size * maxFraction).toInt().coerceAtLeast(1)
        val kept = ArrayList<Long>(rankedIds.size)
        val deferred = ArrayList<Long>()
        var builtinKept = 0
        for (id in rankedIds) {
            if (isBuiltin(id)) {
                if (builtinKept < maxBuiltin) {
                    kept += id
                    builtinKept++
                } else {
                    deferred += id
                }
            } else {
                kept += id
            }
        }
        return kept + deferred
    }
}
