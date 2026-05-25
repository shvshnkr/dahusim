package fr.husi.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportLinkClassifierTest {

    @Test
    fun `github raw url is subscription`() {
        val url = "https://raw.githubusercontent.com/foo/bar/main/list.txt"
        assertTrue(ImportLinkClassifier.looksLikeSubscriptionUrl(url))
        assertEquals("list", ImportLinkClassifier.suggestImportGroupName(url))
    }

    @Test
    fun `provider subscription path hint`() {
        assertTrue(ImportLinkClassifier.looksLikeSubscriptionUrl("https://cdn.example.com/api/v1/client/sub/abc"))
    }

    @Test
    fun `sing-box import remote is subscription`() {
        assertTrue(
            ImportLinkClassifier.looksLikeSubscriptionUrl(
                "sing-box://import-remote-profile?url=https%3A%2F%2Fexample.com%2Fsub",
            ),
        )
    }

    @Test
    fun `suggest group name from gist path`() {
        assertEquals(
            "AetrisVPN",
            ImportLinkClassifier.suggestImportGroupName(
                "https://gist.githubusercontent.com/u/raw/AetrisVPN.txt",
            ),
        )
    }
}
