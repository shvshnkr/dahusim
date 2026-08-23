package fr.husi.group

import fr.husi.bg.SubscriptionUpdateFetchOverrides
import fr.husi.fmt.FmtTestConstant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionHttpFetchTest {

    @Test
    fun `fetchText rejects JsonPanel transport`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            SubscriptionHttpFetch.fetchText(
                SubscriptionHttpFetch.Request(
                    canonicalLink = "https://example.com/sip008",
                    userAgent = "test",
                    transport = SubscriptionHttpFetch.SubscriptionFetchTransport.JsonPanel,
                ),
            )
        }
    }

    @Test
    fun `Request defaults to TextFeed transport`() {
        val request = SubscriptionHttpFetch.Request(
            canonicalLink = "https://raw.githubusercontent.com/foo/bar.txt",
            userAgent = "husi/test",
        )
        assertEquals(SubscriptionHttpFetch.SubscriptionFetchTransport.TextFeed, request.transport)
        assertEquals(SubscriptionHttpFetch.FetchPurpose.GroupUpdate, request.purpose)
    }

    @Test
    fun `Request explicit wl vpn and catalog purpose`() {
        val request = SubscriptionHttpFetch.Request(
            canonicalLink = "https://raw.githubusercontent.com/foo/bar.txt",
            userAgent = "ua",
            whitelistRestricted = true,
            vpnConnected = false,
            purpose = SubscriptionHttpFetch.FetchPurpose.Catalog,
            logContext = "catalog",
        )
        assertEquals(SubscriptionHttpFetch.FetchPurpose.Catalog, request.purpose)
        assertEquals(true, request.whitelistRestricted)
        assertEquals(false, request.vpnConnected)
        assertEquals("catalog", request.logContext)
    }

    @Test
    fun `catalog github raw uses yandex mirror on wl without vpn`() {
        val link =
            "https://raw.githubusercontent.com/shvshnkr/dahusim/main/docs/subscription-catalog.txt"
        val fetchLink = WhitelistSubscriptionFetch.resolveFetchLink(link, whitelistRestricted = true, vpnConnected = false)
        assertTrue(fetchLink.contains("translate.yandex"))
        val direct = WhitelistSubscriptionFetch.resolveFetchLink(link, whitelistRestricted = false, vpnConnected = false)
        assertEquals(link, direct)
    }

    @Test
    fun `buildTextFeedResponse passes plain text through`() {
        val plain = "${FmtTestConstant.VLESS_GRPC_URL}\n${FmtTestConstant.VMESS_DUCKSOFT_URL}"
        val link = "https://raw.githubusercontent.com/foo/bar.txt"
        val response = SubscriptionHttpFetch.buildTextFeedResponse(
            raw = plain,
            canonicalLink = link,
            fetchLink = link,
            subscriptionUserInfo = null,
        )
        assertEquals(plain, response.body)
        assertFalse(response.viaYandexMirror)
        assertEquals(link, response.fetchLink)
        assertEquals(plain.length, response.rawContentLength)
        assertNull(response.subscriptionUserInfo)
    }

    @Test
    fun `buildTextFeedResponse extracts uris from translator html`() {
        val html = """
            <html><body>
            vless://info@0.0.0.0:443?type=tcp&amp;security=none#wifi
            ${FmtTestConstant.VLESS_GRPC_URL.replace("&", "&amp;")}
            </body></html>
        """.trimIndent()
        val link = "https://raw.githubusercontent.com/foo/bar.txt"
        val mirror = WhitelistSubscriptionFetch.yandexTranslateUrl(link)
        val response = SubscriptionHttpFetch.buildTextFeedResponse(
            raw = html,
            canonicalLink = link,
            fetchLink = mirror,
            subscriptionUserInfo = " upload=1 ",
        )
        assertTrue(response.viaYandexMirror)
        assertEquals(mirror, response.fetchLink)
        assertEquals(html.length, response.rawContentLength)
        assertEquals(" upload=1 ", response.subscriptionUserInfo)
        assertEquals(2, response.body.lines().count { it.contains("://") })
        assertTrue(response.body.contains("vless://info@0.0.0.0:443?type=tcp&security=none"))
    }

    @Test
    fun `buildTextFeedResponse drops blank Subscription-Userinfo`() {
        val response = SubscriptionHttpFetch.buildTextFeedResponse(
            raw = "vless://a@1.1.1.1:443#x",
            canonicalLink = "https://example.com/sub.txt",
            fetchLink = "https://example.com/sub.txt",
            subscriptionUserInfo = "   ",
        )
        assertNull(response.subscriptionUserInfo)
    }

    @Test
    fun `wl github fetch falls back to direct when mirror fails`() = runBlocking {
        val canonical =
            "https://raw.githubusercontent.com/shvshnkr/dahusim/main/docs/subscription-catalog.txt"
        val body = "${FmtTestConstant.VLESS_GRPC_URL}"
        SubscriptionFetchTestHooks.install(
            bodyByLink = mapOf(canonical to body),
            failForFetchLinks = setOf(WhitelistSubscriptionFetch.yandexTranslateUrl(canonical)),
        )
        try {
            val response = SubscriptionHttpFetch.fetchText(
                SubscriptionHttpFetch.Request(
                    canonicalLink = canonical,
                    userAgent = "test",
                    whitelistRestricted = true,
                    vpnConnected = false,
                ),
            )
            assertEquals(canonical, response.fetchLink)
            assertFalse(response.viaYandexMirror)
            assertTrue(response.body.contains("vless://"))
        } finally {
            SubscriptionFetchTestHooks.clear()
        }
    }

    @Test
    fun `fetch propagates failure when mirror and direct both fail`() = runBlocking {
        val canonical =
            "https://raw.githubusercontent.com/shvshnkr/dahusim/main/docs/subscription-catalog.txt"
        SubscriptionFetchTestHooks.install(
            bodyByLink = emptyMap(),
            failForFetchLinks = setOf(
                WhitelistSubscriptionFetch.yandexTranslateUrl(canonical),
                canonical,
            ),
        )
        try {
            val failure = assertFailsWith<IllegalStateException> {
                SubscriptionHttpFetch.fetchText(
                    SubscriptionHttpFetch.Request(
                        canonicalLink = canonical,
                        userAgent = "test",
                        whitelistRestricted = true,
                        vpnConnected = false,
                    ),
                )
            }
            assertTrue(failure.message.orEmpty().contains("test hook"))
        } finally {
            SubscriptionFetchTestHooks.clear()
        }
    }

    @Test
    fun `direct fetch stays single attempt without mirror`() = runBlocking {
        val canonical = "https://gitverse.ru/example/feed.txt"
        val body = "${FmtTestConstant.VLESS_GRPC_URL}"
        SubscriptionFetchTestHooks.install(
            bodyByLink = mapOf(canonical to body),
            failForFetchLinks = setOf(canonical),
        )
        try {
            val failure = assertFailsWith<IllegalStateException> {
                SubscriptionHttpFetch.fetchText(
                    SubscriptionHttpFetch.Request(
                        canonicalLink = canonical,
                        userAgent = "test",
                        whitelistRestricted = true,
                        vpnConnected = false,
                    ),
                )
            }
            assertTrue(failure.message.orEmpty().contains("test hook"))
        } finally {
            SubscriptionFetchTestHooks.clear()
        }
    }

    @Test
    fun `fetch applies preconnect timeout override`() = runBlocking {
        val canonical = "https://etoneya.su/whitelist"
        SubscriptionFetchTestHooks.install(
            bodyByLink = mapOf(canonical to "${FmtTestConstant.VLESS_GRPC_URL}"),
        )
        val previous = SubscriptionUpdateFetchOverrides.fetchTimeoutMs
        try {
            SubscriptionUpdateFetchOverrides.fetchTimeoutMs = 2800
            SubscriptionHttpFetch.fetchText(
                SubscriptionHttpFetch.Request(
                    canonicalLink = canonical,
                    userAgent = "test",
                    whitelistRestricted = true,
                    vpnConnected = false,
                ),
            )
            assertEquals(2800, SubscriptionFetchTestHooks.lastTimeoutMs)
        } finally {
            SubscriptionUpdateFetchOverrides.fetchTimeoutMs = previous
            SubscriptionFetchTestHooks.clear()
        }
    }

    @Test
    fun `fetch falls back to default timeout without override`() = runBlocking {
        val canonical = "https://raw.githubusercontent.com/foo/bar.txt"
        SubscriptionFetchTestHooks.install(
            bodyByLink = mapOf(canonical to "${FmtTestConstant.VLESS_GRPC_URL}"),
        )
        val previous = SubscriptionUpdateFetchOverrides.fetchTimeoutMs
        try {
            SubscriptionUpdateFetchOverrides.fetchTimeoutMs = null
            SubscriptionHttpFetch.fetchText(
                SubscriptionHttpFetch.Request(
                    canonicalLink = canonical,
                    userAgent = "test",
                    whitelistRestricted = true,
                    vpnConnected = false,
                ),
            )
            assertEquals(SubscriptionHttpFetch.DEFAULT_FETCH_TIMEOUT_MS, SubscriptionFetchTestHooks.lastTimeoutMs)
        } finally {
            SubscriptionUpdateFetchOverrides.fetchTimeoutMs = previous
            SubscriptionFetchTestHooks.clear()
        }
    }

    @Test
    fun `per-request timeout wins over override`() = runBlocking {
        val canonical = "https://raw.githubusercontent.com/foo/bar.txt"
        SubscriptionFetchTestHooks.install(
            bodyByLink = mapOf(canonical to "${FmtTestConstant.VLESS_GRPC_URL}"),
        )
        val previous = SubscriptionUpdateFetchOverrides.fetchTimeoutMs
        try {
            SubscriptionUpdateFetchOverrides.fetchTimeoutMs = 2800
            SubscriptionHttpFetch.fetchText(
                SubscriptionHttpFetch.Request(
                    canonicalLink = canonical,
                    userAgent = "test",
                    whitelistRestricted = true,
                    vpnConnected = false,
                    timeoutMs = 900,
                ),
            )
            assertEquals(900, SubscriptionFetchTestHooks.lastTimeoutMs)
        } finally {
            SubscriptionUpdateFetchOverrides.fetchTimeoutMs = previous
            SubscriptionFetchTestHooks.clear()
        }
    }
}
