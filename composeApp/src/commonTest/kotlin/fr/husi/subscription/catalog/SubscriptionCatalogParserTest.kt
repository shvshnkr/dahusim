package fr.husi.subscription.catalog

import fr.husi.SubscriptionType
import fr.husi.group.SubscriptionFetchProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubscriptionCatalogParserTest {

    @Test
    fun `parse valid catalog`() {
        val raw = """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=42
            allow_empty=false
            UPSERT|mifa-main|Mifa Main|https://mifa.world/vless|RAW|default
            UPSERT|paid-main|Paid Main|https://example.com/sub|SIP008|happ
            REMOVE|old-id
        """.trimIndent()

        val document = SubscriptionCatalogParser.parse(raw)
        assertEquals(42L, document.generation)
        assertEquals(false, document.allowEmpty)
        assertEquals(3, document.entries.size)

        val first = document.entries[0] as SubscriptionCatalogEntry.Upsert
        assertEquals("mifa-main", first.sourceId)
        assertEquals(SubscriptionType.RAW, first.subscriptionType)
        assertEquals(SubscriptionFetchProfile.DEFAULT, first.fetchProfile)
    }

    @Test
    fun `reject duplicate source id`() {
        val raw = """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=1
            UPSERT|dup-id|One|https://a.example/sub|RAW|default
            REMOVE|dup-id
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> {
            SubscriptionCatalogParser.parse(raw)
        }
    }

    @Test
    fun `reject unsupported fetch profile`() {
        val raw = """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=1
            UPSERT|x-id|X|https://a.example/sub|RAW|unknown
        """.trimIndent()
        assertFailsWith<IllegalStateException> {
            SubscriptionCatalogParser.parse(raw)
        }
    }

    @Test
    fun `reject duplicate upsert links`() {
        val raw = """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=1
            UPSERT|a-id|A|https://example.com/sub|RAW|default
            UPSERT|b-id|B|https://example.com/sub|RAW|default
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> {
            SubscriptionCatalogParser.parse(raw)
        }
    }

    /** Mirrors docs/SUBSCRIPTION_SOURCES_LOCAL.md §4.1 starter catalog. */
    @Test
    fun `parse starter catalog from local handbook`() {
        val raw = """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=1
            allow_empty=false
            # GitHub RAW
            UPSERT|mifa-vless|Mifa VLESS|https://mifa.world/vless|RAW|default
            UPSERT|mifa-hy|Mifa Hysteria|https://mifa.world/hysteria|RAW|default
            UPSERT|paid-main|Paid subscription|https://sub.example-provider.net/api/sub|SIP008|happ
        """.trimIndent()

        val document = SubscriptionCatalogParser.parse(raw)
        assertEquals(1L, document.generation)
        assertEquals(3, document.entries.size)

        val paid = document.entries[2] as SubscriptionCatalogEntry.Upsert
        assertEquals("paid-main", paid.sourceId)
        assertEquals(SubscriptionType.SIP008, paid.subscriptionType)
        assertEquals(SubscriptionFetchProfile.HAPP, paid.fetchProfile)
    }

    /** Mirrors docs/SUBSCRIPTION_SOURCES_LOCAL.md §4.3 custom User-Agent. */
    @Test
    fun `parse custom fetch profile with user agent field`() {
        val raw = """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=3
            allow_empty=false
            UPSERT|corp-panel|Corp panel|https://vpn.corp.example/sub|RAW|custom|MyCorpVPN/1.0
        """.trimIndent()

        val upsert = SubscriptionCatalogParser.parse(raw).entries.single() as SubscriptionCatalogEntry.Upsert
        assertEquals(SubscriptionFetchProfile.CUSTOM, upsert.fetchProfile)
        assertEquals("MyCorpVPN/1.0", upsert.customUserAgent)
    }

    /** Mirrors docs/SUBSCRIPTION_SOURCES_LOCAL.md §4.5 explicit REMOVE. */
    @Test
    fun `parse catalog with explicit remove`() {
        val raw = """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=4
            allow_empty=false
            UPSERT|mifa-vless|Mifa VLESS|https://mifa.world/vless|RAW|default
            REMOVE|paid-main
        """.trimIndent()

        val document = SubscriptionCatalogParser.parse(raw)
        assertEquals(2, document.entries.size)
        assertEquals("paid-main", (document.entries[1] as SubscriptionCatalogEntry.Remove).sourceId)
    }

    @Test
    fun `reject duplicate link with different host casing`() {
        val raw = """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=1
            UPSERT|a-id|A|https://Example.com/sub|RAW|default
            UPSERT|b-id|B|https://example.com/sub|RAW|default
        """.trimIndent()
        assertFailsWith<IllegalArgumentException> {
            SubscriptionCatalogParser.parse(raw)
        }
    }

    @Test
    fun `parse allow_empty true header`() {
        val raw = """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=100
            allow_empty=true
        """.trimIndent()
        val document = SubscriptionCatalogParser.parse(raw)
        assertEquals(true, document.allowEmpty)
        assertEquals(0, document.entries.size)
    }
}
