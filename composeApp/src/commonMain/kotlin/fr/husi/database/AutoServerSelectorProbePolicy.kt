package fr.husi.database

import fr.husi.simplemode.SimpleModeHealthRoute

/**
 * When [AutoServerSelector] must run a full TCP + URL probe pass before connect.
 */
internal object AutoServerSelectorProbePolicy {

    private const val FULL_PROBE_INTERVAL_MS = 18L * 60 * 60 * 1000
    private const val LAST_KNOWN_GOOD_URL_STALE_MS = 48L * 60 * 60 * 1000
    private const val PROXY_SET_CHANGE_GRACE_MS = 3L * 60 * 1000
    private const val HANDOFF_PRESERVE_FRESH_MS = 20_000L
    private const val DEGRADED_PROFILE_PENALTY_MS = 45L * 60 * 1000
    private const val TELEGRAM_TARGET_WINDOW_SIZE = 24
    private const val TELEGRAM_TARGET_MIN_SAMPLES = 8
    private const val TELEGRAM_TARGET_FAIL_RATIO_THRESHOLD = 0.85
    private const val TELEGRAM_TARGET_COOLDOWN_MS = 90_000L

    enum class OpenPrepareDecision {
        HARD_DEAD,
        DEGRADED,
        OK,
    }

    data class OpenPrepareDecisionOutcome(
        val decision: OpenPrepareDecision,
        val circuitOpen: Boolean,
        val successRatio: Double,
        val sampleCount: Int,
    )

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

    fun isHandoffPreserveFresh(nowMs: Long = System.currentTimeMillis()): Boolean {
        val preservedAt = DataStore.autoSelectLastHandoffPreserveOkAt
        return preservedAt > 0L && nowMs - preservedAt < HANDOFF_PRESERVE_FRESH_MS
    }

    fun recordHandoffPreserveSuccess(nowMs: Long = System.currentTimeMillis()) {
        DataStore.autoSelectLastHandoffPreserveOkAt = nowMs
    }

    fun isRecentlyDegraded(
        profileId: Long,
        cachedDegradedProfileId: Long = DataStore.autoSelectLastDegradedProfileId,
        cachedDegradedAt: Long = DataStore.autoSelectLastDegradedAt,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (profileId <= 0L) return false
        if (cachedDegradedProfileId != profileId) return false
        return cachedDegradedAt > 0L && nowMs - cachedDegradedAt < DEGRADED_PROFILE_PENALTY_MS
    }

    fun recordDegradedProfile(profileId: Long, nowMs: Long = System.currentTimeMillis()) {
        if (profileId <= 0L) return
        DataStore.autoSelectLastDegradedProfileId = profileId
        DataStore.autoSelectLastDegradedAt = nowMs
    }

    fun clearDegradedProfile(profileId: Long) {
        if (profileId <= 0L) return
        if (DataStore.autoSelectLastDegradedProfileId == profileId) {
            DataStore.autoSelectLastDegradedProfileId = 0L
            DataStore.autoSelectLastDegradedAt = 0L
        }
    }

    @Synchronized
    fun decideOpenPrepare(
        wlUrlProbes: Boolean,
        shouldQuickProbe: Boolean,
        tcpAlive: Int,
        urlOk: Int,
        openMessengerProbe: Boolean = SimpleModeHealthRoute.messengerProbeRequired(false),
    ): OpenPrepareDecisionOutcome {
        if (wlUrlProbes || !openMessengerProbe || !shouldQuickProbe) {
            return OpenPrepareDecisionOutcome(
                decision = OpenPrepareDecision.OK,
                circuitOpen = false,
                successRatio = TelegramTargetCircuit.successRatio(),
                sampleCount = TelegramTargetCircuit.sampleCount(),
            )
        }
        if (tcpAlive <= 0) {
            return OpenPrepareDecisionOutcome(
                decision = OpenPrepareDecision.HARD_DEAD,
                circuitOpen = TelegramTargetCircuit.isOpen(),
                successRatio = TelegramTargetCircuit.successRatio(),
                sampleCount = TelegramTargetCircuit.sampleCount(),
            )
        }
        val success = urlOk > 0
        TelegramTargetCircuit.record(success)
        val circuitOpen = TelegramTargetCircuit.isOpen()
        val decision = when {
            success -> OpenPrepareDecision.OK
            !circuitOpen -> OpenPrepareDecision.HARD_DEAD
            else -> OpenPrepareDecision.DEGRADED
        }
        return OpenPrepareDecisionOutcome(
            decision = decision,
            circuitOpen = circuitOpen,
            successRatio = TelegramTargetCircuit.successRatio(),
            sampleCount = TelegramTargetCircuit.sampleCount(),
        )
    }

    fun wlPrepareHasUrlConfirmation(
        profileId: Long,
        urlTestDelays: Map<Long, Int>,
        probeStates: Map<Long, ProxyProbeState>,
        lkgUrlFresh: (Long) -> Boolean = ::isLastKnownGoodUrlFresh,
    ): Boolean {
        if (profileId in urlTestDelays) return true
        val state = probeStates[profileId]
        if (state != null && state.lastUrlMs > 0) {
            val now = System.currentTimeMillis()
            if (now - state.lastOkAt <= Probe2kDefaults.ALIVE_URL_FRESH_MS) return true
        }
        return lkgUrlFresh(profileId)
    }

    /**
     * A prepare sweep that ends with 0 url-ok must dead-end (AllProbesDead) instead of
     * DEGRADED-continuing into a tcp-alive node when the uplink is whitelist-restricted:
     * BS servers flap on a minute scale, and a tcp-only candidate yields a dead tunnel +
     * post_connect fail. WL_SUBSCRIPTION/MERGED reach this via [wlUrlProbes]; the OPEN
     * fallback branch (H4 wl_pool_fallback_open_priority_once) reaches it via
     * [activeWhitelistRestrictedNetwork]. Dead-ending lets the revival watch keep polling
     * and auto-connect the moment any candidate verifies.
     *
     * Applies to every URL sweep that found nothing, including the sequential sweep that
     * runs after a skipped quick probe (shouldQuickProbe=false): a 0-url-ok sweep is fresh
     * negative evidence either way. [urlConfirmed] must not count confirmation the current
     * sweep contradicted — the caller drops warm-state confirmation when the best candidate
     * itself was just tested and failed.
     */
    fun wlNoUrlOkDeadEndsPrepare(
        wlUrlProbes: Boolean,
        activeWhitelistRestrictedNetwork: Boolean,
        urlOk: Int,
        urlConfirmed: Boolean,
    ): Boolean = urlOk <= 0 && !urlConfirmed &&
        (wlUrlProbes || activeWhitelistRestrictedNetwork)

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
        if (networkHandoff &&
            DataStore.autoSelectLastProbeWhitelistOnly != whitelistBuiltinOnly
        ) {
            reasons += if (whitelistBuiltinOnly) "open_to_wl" else "wl_to_open"
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
            if (!DataStore.autoSelectLastProbeWhitelistOnly && whitelistBuiltinOnly) {
                reasons += "open_to_wl"
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

    internal object TelegramTargetCircuit {
        private val results = ArrayDeque<Boolean>()
        private var openUntilMs: Long = 0L

        fun record(success: Boolean) {
            if (results.size >= TELEGRAM_TARGET_WINDOW_SIZE) {
                results.removeFirst()
            }
            results.addLast(success)
            val ratio = successRatio()
            val enough = results.size >= TELEGRAM_TARGET_MIN_SAMPLES
            if (enough && ratio <= (1.0 - TELEGRAM_TARGET_FAIL_RATIO_THRESHOLD)) {
                openUntilMs = System.currentTimeMillis() + TELEGRAM_TARGET_COOLDOWN_MS
            }
        }

        fun isOpen(nowMs: Long = System.currentTimeMillis()): Boolean {
            if (openUntilMs <= nowMs) {
                openUntilMs = 0L
                return false
            }
            return true
        }

        fun successRatio(): Double {
            if (results.isEmpty()) return 1.0
            val successCount = results.count { it }
            return successCount.toDouble() / results.size.toDouble()
        }

        fun sampleCount(): Int = results.size

        fun resetForTest() {
            results.clear()
            openUntilMs = 0L
        }

        fun seedOpenForTest(open: Boolean) {
            openUntilMs = if (open) {
                System.currentTimeMillis() + TELEGRAM_TARGET_COOLDOWN_MS
            } else {
                0L
            }
        }
    }
}
