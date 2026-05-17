package fr.husi.group

import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionSourceTest {

    @Test
    fun `infer github source kind`() {
        assertEquals(
            SubscriptionSourceKind.GITHUB,
            SubscriptionSourceKind.inferFromLink("https://raw.githubusercontent.com/foo/bar/main/a.txt"),
        )
        assertEquals(
            SubscriptionSourceKind.GITHUB,
            SubscriptionSourceKind.inferFromLink("https://gist.githubusercontent.com/u/1/raw/file.txt"),
        )
    }

    @Test
    fun `infer generic web source kind`() {
        assertEquals(
            SubscriptionSourceKind.WEB,
            SubscriptionSourceKind.inferFromLink("https://mifa.world/vless"),
        )
    }
}
