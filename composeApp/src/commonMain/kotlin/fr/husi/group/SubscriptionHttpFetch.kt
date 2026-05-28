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
            setURL(fetchLink)
            Logs.d("subscription fetch UA (${request.purpose.name}): ${request.userAgent}")
            setUserAgent(request.userAgent)
        }.execute()

        return buildTextFeedResponse(
            raw = response.contentString,
            canonicalLink = request.canonicalLink,
            fetchLink = fetchLink,
            subscriptionUserInfo = response.getHeader("Subscription-Userinfo"),
        )
    }

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
