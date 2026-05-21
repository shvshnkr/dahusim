package fr.husi.database

/**
 * Limits built-in whitelist nodes in the auto-select fallback queue so subscription
 * profiles are not starved when the pool grows toward 2K nodes.
 */
object BuiltinFallbackQuota {

    fun apply(
        rankedIds: List<Long>,
        builtinProfileIds: Set<Long>,
        maxFraction: Double = Probe2kDefaults.BUILTIN_FALLBACK_MAX_FRACTION,
        enabled: Boolean = DataStore.probe2kBuiltinFallbackCapEnabled,
    ): List<Long> {
        if (!enabled || rankedIds.isEmpty()) return rankedIds
        if (builtinProfileIds.isEmpty()) return rankedIds
        val maxBuiltin = (rankedIds.size * maxFraction).toInt().coerceIn(1, rankedIds.size)
        val subscription = ArrayList<Long>(rankedIds.size)
        val builtin = ArrayList<Long>(rankedIds.size)
        for (id in rankedIds) {
            if (id in builtinProfileIds) builtin += id else subscription += id
        }
        if (builtin.size <= maxBuiltin) return rankedIds
        val cappedBuiltin = builtin.take(maxBuiltin)
        val overflowBuiltin = builtin.drop(maxBuiltin)
        return subscription + cappedBuiltin + overflowBuiltin
    }
}
