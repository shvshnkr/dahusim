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
}
