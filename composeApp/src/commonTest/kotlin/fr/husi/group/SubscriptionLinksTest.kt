package fr.husi.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionLinksTest {

    @Test
    fun `single url passes through`() {
        val link = "https://example.com/feed.txt"
        assertEquals(listOf(link), SubscriptionLinks.splitMirrors(link))
        assertEquals(link, SubscriptionLinks.firstLink(link))
    }

    @Test
    fun `pipe separated mirrors split and keep order`() {
        val link = "https://a.example/1.txt|https://b.example/2.txt|https://c.example/3.txt"
        assertEquals(
            listOf("https://a.example/1.txt", "https://b.example/2.txt", "https://c.example/3.txt"),
            SubscriptionLinks.splitMirrors(link),
        )
        assertEquals("https://a.example/1.txt", SubscriptionLinks.firstLink(link))
    }

    @Test
    fun `whitespace separated mirrors split for catalog field`() {
        val link = "https://a.example/1.txt https://b.example/2.txt\thttps://c.example/3.txt"
        assertEquals(
            listOf("https://a.example/1.txt", "https://b.example/2.txt", "https://c.example/3.txt"),
            SubscriptionLinks.splitMirrors(link),
        )
    }

    @Test
    fun `mixed separators and duplicates collapse`() {
        val link = "https://a.example/1.txt| https://b.example/2.txt https://a.example/1.txt"
        assertEquals(
            listOf("https://a.example/1.txt", "https://b.example/2.txt"),
            SubscriptionLinks.splitMirrors(link),
        )
    }

    @Test
    fun `non url junk tokens dropped`() {
        val link = "https://a.example/1.txt junk note|https://b.example/2.txt"
        assertEquals(
            listOf("https://a.example/1.txt", "https://b.example/2.txt"),
            SubscriptionLinks.splitMirrors(link),
        )
    }

    @Test
    fun `content uri preserved as canonical`() {
        val link = "content://authority/path"
        assertEquals(listOf(link), SubscriptionLinks.splitMirrors(link))
        assertEquals(link, SubscriptionLinks.firstLink(link))
    }

    @Test
    fun `blank link falls back to raw trim in firstLink`() {
        assertEquals("", SubscriptionLinks.firstLink(""))
        assertTrue(SubscriptionLinks.splitMirrors("   ").isEmpty())
    }
}
