package fr.husi.group

import fr.husi.bg.SubscriptionUpdateFetchOverrides
import fr.husi.database.DataStore
import fr.husi.ktx.Logs
import fr.husi.libcore.Libcore
import fr.husi.utils.simpleModeLog

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
         * Per-request HTTP timeout (ms); null → [SubscriptionUpdateFetchOverrides.fetchTimeoutMs]
         * for the current update, else the default (15s, mirrors libcore C.TCPTimeout).
         */
        val timeoutMs: Int? = null,
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
            link = request.canonicalLink,
            whitelistRestricted = whitelistRestricted,
            vpnConnected = vpnConnected,
        )
        val viaMirror = fetchLink != request.canonicalLink
        if (viaMirror) {
            val ctx = request.logContext?.let { " $it" }.orEmpty()
            simpleModeLog(
                "SimpleMode",
                "H29 subscription_fetch_mirror yandex purpose=${request.purpose.name.lowercase()}$ctx " +
                    "host=${request.canonicalLink.substringBefore('?')}",
            )
        }

        // Bounded fallback chain: primary (mirror on WL) first, then the canonical link. The
        // whitelist mirror is flaky (field: 2026-08-18 02:46/02:55 preconnect refresh timeouts
        // on BS killed 8 fetches mid-flight); the direct host is sometimes reachable after all.
        val attempts = linkedSetOf(fetchLink, request.canonicalLink)
        var lastFailure: Throwable? = null
        for (link in attempts) {
            try {
                val raw = rawFetch(request, link, vpnConnected)
                return buildTextFeedResponse(
                    raw = raw.content,
                    canonicalLink = request.canonicalLink,
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
        link: String,
        vpnConnected: Boolean,
    ): RawFetch {
        val timeoutMs = request.timeoutMs
            ?: SubscriptionUpdateFetchOverrides.fetchTimeoutMs
            ?: DEFAULT_FETCH_TIMEOUT_MS
        if (SubscriptionFetchTestHooks.enabled) {
            SubscriptionFetchTestHooks.lastTimeoutMs = timeoutMs
        }
        if (SubscriptionFetchTestHooks.shouldFailFetch(link)) {
            throw IllegalStateException("test hook: fetch failed for $link")
        }
        SubscriptionFetchTestHooks.bodyFor(request.canonicalLink)?.let { body ->
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
