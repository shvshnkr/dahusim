package fr.husi.group

import fr.husi.bg.SubscriptionUpdateFetchOverrides
import fr.husi.database.DataStore
import fr.husi.ktx.Logs
import fr.husi.libcore.Libcore
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Unified HTTP fetch for text subscription feeds (RAW, catalog, import preview).
 *
 * Policy (mirror, HTML extract) lives in [WhitelistSubscriptionFetch].
 * [SubscriptionFetchTransport.JsonPanel] (SIP008 / OOC: restrictedTLS, no mirror) is reserved
 * for a later migration — updaters still use local [Libcore.newHttpClient] until then.
 */
object SubscriptionHttpFetch {

    enum class SubscriptionFetchTransport {
        /** RAW / catalog / import: Yandex mirror on WL uplink, [WhitelistSubscriptionFetch.extractSubscriptionBody]. */
        TextFeed,

        /**
         * Planned: [fr.husi.group.SIP008Updater], [fr.husi.group.OpenOnlineConfigUpdater].
         * Direct HTTPS to panel URL, `restrictedTLS()`, optional cert pin — no translate mirror.
         */
        JsonPanel,
    }

    enum class FetchPurpose {
        GroupUpdate,
        Catalog,
        ImportPreview,
    }

    data class Request(
        val canonicalLink: String,
        val userAgent: String,
        val purpose: FetchPurpose = FetchPurpose.GroupUpdate,
        val transport: SubscriptionFetchTransport = SubscriptionFetchTransport.TextFeed,
        /** null → [DataStore.activeWhitelistRestrictedNetwork] at fetch time. */
        val whitelistRestricted: Boolean? = null,
        /** null → [DataStore.serviceState.connected] at fetch time. */
        val vpnConnected: Boolean? = null,
        /**
         * Per-request HTTP timeout (ms); null → default (15s, mirrors libcore C.TCPTimeout).
         */
        val timeoutMs: Int? = null,
        /** Ordered mirror candidates raced in parallel after [canonicalLink]; first success wins. */
        val mirrorLinks: List<String> = emptyList(),
        val logContext: String? = null,
    )

    data class Response(
        val body: String,
        val subscriptionUserInfo: String?,
        val viaYandexMirror: Boolean,
        val fetchLink: String,
        val rawContentLength: Int,
    )

    suspend fun fetchText(request: Request): Response {
        val candidates = (listOf(request.canonicalLink) + request.mirrorLinks)
            .flatMap { SubscriptionLinks.splitMirrors(it) }
            .distinct()
        return if (candidates.size <= 1) {
            fetchTextSingle(request, candidates.firstOrNull() ?: request.canonicalLink)
        } else {
            fetchTextRace(request, candidates)
        }
    }

    /**
     * Fast mirror race (v2whitelist-style): all candidates start in parallel, the first
     * successful fetch wins and the losers are cancelled. Wall time is bounded by the
     * fastest success instead of the sum of timeouts. When every candidate fails, the
     * canonical candidate's failure (or the first one) is rethrown so downstream
     * [fr.husi.database.SubscriptionUpdateStateStore] message classification keeps working.
     */
    private suspend fun fetchTextRace(request: Request, candidates: List<String>): Response =
        coroutineScope {
            simpleModeLog(
                "SimpleMode",
                "H16 subscription_fetch_race purpose=${request.purpose.name.lowercase()} " +
                    "candidates=${candidates.size}${request.logContext?.let { " $it" }.orEmpty()}",
            )
            val winner = Channel<Response>(capacity = 1)
            val failures = HashMap<String, Throwable>()
            val jobs = candidates.map { link ->
                launch(Dispatchers.IO) {
                    try {
                        winner.trySend(fetchTextSingle(request, link))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        synchronized(failures) { failures[link] = e }
                    }
                }
            }
            launch { jobs.joinAll(); winner.close() }
            for (response in winner) {
                jobs.forEach { it.cancel() }
                return@coroutineScope response
            }
            val cause = synchronized(failures) {
                failures[request.canonicalLink] ?: failures.values.firstOrNull()
            }
            throw cause ?: IllegalStateException("no fetch attempts")
        }

    private suspend fun fetchTextSingle(request: Request, canonicalLink: String): Response {
        require(request.transport == SubscriptionFetchTransport.TextFeed) {
            "SubscriptionHttpFetch.fetchText supports TextFeed only; JsonPanel is not implemented"
        }
        val whitelistRestricted = request.whitelistRestricted
            ?: DataStore.activeWhitelistRestrictedNetwork
        val vpnConnected = when {
            SubscriptionUpdateFetchOverrides.bypassVpn -> false
            request.vpnConnected != null -> request.vpnConnected
            else -> DataStore.serviceState.connected
        }
        val fetchLink = WhitelistSubscriptionFetch.resolveFetchLink(
            link = canonicalLink,
            whitelistRestricted = whitelistRestricted,
            vpnConnected = vpnConnected,
        )
        val viaMirror = fetchLink != canonicalLink
        if (viaMirror) {
            val ctx = request.logContext?.let { " $it" }.orEmpty()
            simpleModeLog(
                "SimpleMode",
                "H29 subscription_fetch_mirror yandex purpose=${request.purpose.name.lowercase()}$ctx " +
                    "host=${canonicalLink.substringBefore('?')}",
            )
        }

        // Bounded fallback chain: on BS uplink (mirror path) the canonical GitHub host is
        // L3-blocked, so the mirror is the only reachable path — a second attempt would burn
        // the full per-attempt timeout (audit A.2; field 2026-08-18 02:46/02:55: 8 mirror
        // fetches killed mid-flight by budget). On open RU networks GitHub hosts are
        // DPI-blocked but the mirror is reachable — canonical → mirror fallback (WLZ-S3,
        // field 2026-08-30 11:29-12:02: dozens of group_update_failure on github hosts).
        // Via VPN the canonical works through SOCKS — no mirror. Off BS the fetch link is
        // the canonical itself.
        val mirrorFallback = !whitelistRestricted && !vpnConnected &&
            WhitelistSubscriptionFetch.supportsYandexMirror(canonicalLink)
        val attempts = when {
            whitelistRestricted && !vpnConnected -> linkedSetOf(fetchLink)
            mirrorFallback -> linkedSetOf(
                fetchLink,
                WhitelistSubscriptionFetch.yandexTranslateUrl(canonicalLink),
            )
            else -> linkedSetOf(fetchLink)
        }
        var lastFailure: Throwable? = null
        val defaultTimeoutMs = request.timeoutMs ?: DEFAULT_FETCH_TIMEOUT_MS
        for (link in attempts) {
            // With a mirror fallback, cap the canonical attempt so the mirror still fits the
            // background budget (canonical ≤8s + mirror ≤15s ≤ 25s open refresh budget);
            // explicit preconnect timeouts are already below the cap (no-op).
            val timeoutMs = if (mirrorFallback && link == canonicalLink) {
                minOf(defaultTimeoutMs, CANONICAL_FIRST_ATTEMPT_TIMEOUT_MS)
            } else {
                defaultTimeoutMs
            }
            if (link != canonicalLink && !viaMirror) {
                val ctx = request.logContext?.let { " $it" }.orEmpty()
                simpleModeLog(
                    "SimpleMode",
                    "H29 subscription_fetch_mirror yandex purpose=${request.purpose.name.lowercase()}$ctx " +
                        "host=${canonicalLink.substringBefore('?')} fallback=true",
                )
            }
            try {
                val raw = rawFetch(request, canonicalLink, link, vpnConnected, timeoutMs)
                return buildTextFeedResponse(
                    raw = raw.content,
                    canonicalLink = canonicalLink,
                    fetchLink = link,
                    subscriptionUserInfo = raw.subscriptionUserInfo,
                )
            } catch (e: Throwable) {
                lastFailure = e
                if (attempts.size > 1) {
                    simpleModeLog(
                        "SimpleMode",
                        "H29 subscription_fetch_fallback link=${link.substringBefore('?')} " +
                            "error=${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
        throw lastFailure ?: IllegalStateException("no fetch attempts")
    }

    private data class RawFetch(
        val content: String,
        val subscriptionUserInfo: String?,
    )

    private fun rawFetch(
        request: Request,
        canonicalLink: String,
        link: String,
        vpnConnected: Boolean,
        timeoutMs: Int,
    ): RawFetch {
        if (SubscriptionFetchTestHooks.enabled) {
            SubscriptionFetchTestHooks.lastTimeoutMs = timeoutMs
            SubscriptionFetchTestHooks.lastTimeoutMsByLink[link] = timeoutMs
        }
        if (SubscriptionFetchTestHooks.shouldFailFetch(link)) {
            throw IllegalStateException("test hook: fetch failed for $link")
        }
        SubscriptionFetchTestHooks.bodyFor(canonicalLink)?.let { body ->
            return RawFetch(body, null)
        }
        val response = Libcore.newHttpClient().apply {
            keepAlive()
            if (vpnConnected) {
                useSocks5(
                    DataStore.mixedPort,
                    DataStore.inboundUsername,
                    DataStore.inboundPassword,
                )
            }
        }.newRequest().apply {
            setURL(link)
            Logs.d("subscription fetch UA (${request.purpose.name}): ${request.userAgent}")
            setUserAgent(request.userAgent)
            setTimeout(timeoutMs)
        }.execute()
        return RawFetch(
            content = response.contentString,
            subscriptionUserInfo = response.getHeader("Subscription-Userinfo"),
        )
    }

    /** Mirrors libcore C.TCPTimeout (libcore/deps/sing-box/constant/timeout.go). */
    internal const val DEFAULT_FETCH_TIMEOUT_MS = 15_000

    /**
     * Cap for the canonical attempt when a Yandex mirror fallback exists (WLZ-S3): keeps
     * canonical(≤8s) + mirror(≤15s) inside the open-network background refresh budget (25s).
     */
    internal const val CANONICAL_FIRST_ATTEMPT_TIMEOUT_MS = 8_000

    internal fun buildTextFeedResponse(
        raw: String,
        canonicalLink: String,
        fetchLink: String,
        subscriptionUserInfo: String?,
    ): Response = Response(
        body = WhitelistSubscriptionFetch.extractSubscriptionBody(raw),
        subscriptionUserInfo = subscriptionUserInfo?.takeIf { it.isNotBlank() },
        viaYandexMirror = fetchLink != canonicalLink,
        fetchLink = fetchLink,
        rawContentLength = raw.length,
    )
}
