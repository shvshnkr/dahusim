package fr.husi.group

import fr.husi.fmt.FmtTestConstant
import fr.husi.ktx.urlSafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhitelistSubscriptionFetchTest {

    @Test
    fun `shouldUseYandexMirror only on whitelist net without vpn for github`() {
        val link = "https://raw.githubusercontent.com/nzea243/ikoV31tud_vpn/refs/heads/main/tri_228.txt"
        assertFalse(
            WhitelistSubscriptionFetch.shouldUseYandexMirror(
                link = link,
                whitelistRestricted = false,
                vpnConnected = false,
            ),
        )
        assertFalse(
            WhitelistSubscriptionFetch.shouldUseYandexMirror(
                link = link,
                whitelistRestricted = true,
                vpnConnected = true,
            ),
        )
        assertTrue(
            WhitelistSubscriptionFetch.shouldUseYandexMirror(
                link = link,
                whitelistRestricted = true,
                vpnConnected = false,
            ),
        )
    }

    @Test
    fun `shouldUseYandexMirror skips gitverse and existing yandex url`() {
        val gitverse =
            "https://gitverse.ru/api/repos/bywarm/rser/raw/branch/master/selected.txt"
        assertFalse(
            WhitelistSubscriptionFetch.shouldUseYandexMirror(
                link = gitverse,
                whitelistRestricted = true,
                vpnConnected = false,
            ),
        )
        val wrapped = WhitelistSubscriptionFetch.yandexTranslateUrl(gitverse)
        assertFalse(
            WhitelistSubscriptionFetch.shouldUseYandexMirror(
                link = wrapped,
                whitelistRestricted = true,
                vpnConnected = false,
            ),
        )
    }

    @Test
    fun `shouldUseYandexMirror for dahusim catalog feed on whitelist`() {
        val link =
            "https://raw.githubusercontent.com/shvshnkr/dahusim/main/docs/subscription-catalog.txt"
        assertTrue(
            WhitelistSubscriptionFetch.shouldUseYandexMirror(
                link = link,
                whitelistRestricted = true,
                vpnConnected = false,
            ),
        )
    }

    @Test
    fun `yandexTranslateUrl encodes source link`() {
        val link = "https://raw.githubusercontent.com/foo/bar/refs/heads/main/a.txt"
        val wrapped = WhitelistSubscriptionFetch.yandexTranslateUrl(link)
        assertTrue(wrapped.startsWith("https://translate.yandex.ru/translate?url="))
        assertTrue(wrapped.contains(link.urlSafe()))
        assertTrue(wrapped.endsWith("&lang=en-ru"))
    }

    @Test
    fun `extractSubscriptionBody passes plain text through`() {
        val plain = "${FmtTestConstant.VLESS_GRPC_URL}\n${FmtTestConstant.VMESS_DUCKSOFT_URL}"
        assertEquals(plain, WhitelistSubscriptionFetch.extractSubscriptionBody(plain))
    }

    @Test
    fun `extractSubscriptionBody pulls proxy uris from translator html`() {
        val html = """
            <html><body>
            <p>#profile-title: test</p>
            vless://info@0.0.0.0:443?type=tcp&amp;security=none#wifi
            ${FmtTestConstant.VLESS_GRPC_URL.replace("&", "&amp;")}
            </body></html>
        """.trimIndent()
        val body = WhitelistSubscriptionFetch.extractSubscriptionBody(html)
        assertTrue(body.contains("vless://info@0.0.0.0:443?type=tcp&security=none"))
        assertTrue(body.contains("vless://"))
        assertEquals(2, body.lines().count { it.contains("://") })
    }
}
