package fr.husi.database

/**
 * When [AutoServerSelector] must run a full TCP + URL probe pass before connect.
 */
internal object AutoServerSelectorProbePolicy {

    private const val FULL_PROBE_INTERVAL_MS = 18L * 60 * 60 * 1000
    private const val LAST_KNOWN_GOOD_URL_STALE_MS = 48L * 60 * 60 * 1000

    fun computeProxyIdSetHash(proxies: Collection<ProxyEntity>): Long {
        var hash = 1L
        for (id in proxies.map { it.id }.sorted()) {
            hash = 31L * hash + id
        }
        return hash
    }

    fun forceFullProbeReason(
        proxies: List<ProxyEntity>,
        whitelistBuiltinOnly: Boolean,
    ): String? {
        val reasons = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val lastProbeAt = DataStore.autoSelectLastFullProbeAt
        if (lastProbeAt == 0L || now - lastProbeAt >= FULL_PROBE_INTERVAL_MS) {
            reasons += "interval"
        }
        val hash = computeProxyIdSetHash(proxies)
        val storedHash = DataStore.autoSelectProxyIdSetHash
        if (storedHash != 0L && hash != storedHash) {
            reasons += "proxy_set_changed"
        }
        if (DataStore.autoSelectLastProbeWhitelistOnly && !whitelistBuiltinOnly) {
            reasons += "wl_to_open"
        }
        val goodId = DataStore.autoSelectLastKnownGood
        if (goodId > 0L) {
            val verifiedAt = DataStore.autoSelectLastKnownGoodUrlAt
            val verifiedProfile = DataStore.autoSelectLastKnownGoodUrlProfileId
            val staleVerification = verifiedAt == 0L ||
                verifiedProfile != goodId ||
                now - verifiedAt >= LAST_KNOWN_GOOD_URL_STALE_MS
            if (staleVerification) {
                reasons += "last_known_good_stale"
            }
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
