package fr.husi.database

/**
 * When [AutoServerSelector] must run a full TCP + URL probe pass before connect.
 */
internal object AutoServerSelectorProbePolicy {

    private const val FULL_PROBE_INTERVAL_MS = 18L * 60 * 60 * 1000
    private const val LAST_KNOWN_GOOD_URL_STALE_MS = 48L * 60 * 60 * 1000
    private const val PROXY_SET_CHANGE_GRACE_MS = 3L * 60 * 1000

    fun useCompactReprobeForProxySetChange(
        proxies: List<ProxyEntity>,
        whitelistBuiltinOnly: Boolean,
        networkHandoff: Boolean,
    ): Boolean {
        if (networkHandoff) return false
        if (DataStore.autoSelectLastProbeWhitelistOnly != whitelistBuiltinOnly) return false
        val hash = computeProxyIdSetHash(proxies)
        val storedHash = DataStore.autoSelectProxyIdSetHash
        if (storedHash == 0L || hash == storedHash) return false
        val lastProbeAt = DataStore.autoSelectLastFullProbeAt
        return lastProbeAt > 0L && System.currentTimeMillis() - lastProbeAt < PROXY_SET_CHANGE_GRACE_MS
    }

    fun computeProxyIdSetHash(proxies: Collection<ProxyEntity>): Long {
        var hash = 1L
        for (id in proxies.map { it.id }.sorted()) {
            hash = 31L * hash + id
        }
        return hash
    }

    fun isLastKnownGoodUrlFresh(profileId: Long = DataStore.autoSelectLastKnownGood): Boolean {
        if (profileId <= 0L) return false
        val now = System.currentTimeMillis()
        val verifiedAt = DataStore.autoSelectLastKnownGoodUrlAt
        val verifiedProfile = DataStore.autoSelectLastKnownGoodUrlProfileId
        return verifiedAt > 0L &&
            verifiedProfile == profileId &&
            now - verifiedAt < LAST_KNOWN_GOOD_URL_STALE_MS
    }

    fun forceFullProbeReason(
        proxies: List<ProxyEntity>,
        whitelistBuiltinOnly: Boolean,
        networkHandoff: Boolean = false,
    ): String? {
        val reasons = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val hash = computeProxyIdSetHash(proxies)
        val storedHash = DataStore.autoSelectProxyIdSetHash
        val hashStable = storedHash == 0L || hash == storedHash
        val goodId = DataStore.autoSelectLastKnownGood
        val lkgFresh = goodId > 0L && isLastKnownGoodUrlFresh(goodId)
        val lastProbeAt = DataStore.autoSelectLastFullProbeAt
        if (lastProbeAt == 0L || now - lastProbeAt >= FULL_PROBE_INTERVAL_MS) {
            if (!(lkgFresh && hashStable && !whitelistBuiltinOnly && !networkHandoff)) {
                reasons += "interval"
            }
        }
        if (!networkHandoff) {
            if (storedHash != 0L && hash != storedHash) {
                val recentProbe = lastProbeAt > 0L && now - lastProbeAt < PROXY_SET_CHANGE_GRACE_MS
                val wlModeUnchanged = DataStore.autoSelectLastProbeWhitelistOnly == whitelistBuiltinOnly
                if (!(recentProbe && wlModeUnchanged)) {
                    reasons += "proxy_set_changed"
                }
            }
            if (DataStore.autoSelectLastProbeWhitelistOnly && !whitelistBuiltinOnly) {
                reasons += "wl_to_open"
            }
        }
        if (goodId > 0L && !lkgFresh) {
            reasons += "last_known_good_stale"
        }
        return reasons.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    fun recordFullProbe(proxies: List<ProxyEntity>, whitelistBuiltinOnly: Boolean) {
        DataStore.autoSelectLastFullProbeAt = System.currentTimeMillis()
        DataStore.autoSelectProxyIdSetHash = computeProxyIdSetHash(proxies)
        DataStore.autoSelectLastProbeWhitelistOnly = whitelistBuiltinOnly
    }

    fun recordPostConnectUrlVerified(profileId: Long) {
        val now = System.currentTimeMillis()
        DataStore.autoSelectLastKnownGoodUrlAt = now
        DataStore.autoSelectLastKnownGoodUrlProfileId = profileId
    }
}
