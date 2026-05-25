package fr.husi.ui

import fr.husi.fmt.FmtTestConstant
import fr.husi.fmt.v2ray.VLESSBean
import fr.husi.fmt.v2ray.VMessBean
import fr.husi.ui.ImportLinkClassifier.HttpImportResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ImportLinkClassifierClassifyTest {

    private val githubList =
        "https://raw.githubusercontent.com/foo/bar/main/list.txt"

    @Test
    fun `classifyParsedProxies multi proxy on github is subscription`() {
        val proxies = listOf(
            VLESSBean().apply { uuid = "a" },
            VMessBean().apply { uuid = "b" },
        )
        assertEquals(
            HttpImportResolution.Subscription(githubList),
            ImportLinkClassifier.classifyParsedProxies(proxies, githubList),
        )
    }

    @Test
    fun `classifyParsedProxies single proxy on github is standalone`() {
        val bean = VLESSBean().apply {
            uuid = FmtTestConstant.VLESS_GRPC_URL.substringAfter("vless://").substringBefore("@")
        }
        val resolution = ImportLinkClassifier.classifyParsedProxies(listOf(bean), githubList)
        val standalone = assertIs<HttpImportResolution.Standalone>(resolution)
        assertEquals(1, standalone.proxies.size)
        assertEquals("list", standalone.suggestedGroupName)
    }

    @Test
    fun `classifyParsedProxies null on github falls back to subscription`() {
        assertEquals(
            HttpImportResolution.Subscription(githubList),
            ImportLinkClassifier.classifyParsedProxies(null, githubList),
        )
    }

    @Test
    fun `classifyParsedProxies empty on non subscription url is ambiguous`() {
        val url = "https://cdn.example.com/static/page.html"
        assertEquals(
            HttpImportResolution.Ambiguous,
            ImportLinkClassifier.classifyParsedProxies(emptyList(), url),
        )
    }

    @Test
    fun `classifyParsedProxies two proxies on provider path is subscription`() {
        val url = "https://cdn.example.com/api/v1/client/sub/abc"
        val proxies = listOf(VLESSBean(), VMessBean())
        assertEquals(
            HttpImportResolution.Subscription(url),
            ImportLinkClassifier.classifyParsedProxies(proxies, url),
        )
    }

    @Test
    fun `classifyParsedProxies unparseable empty on provider path is subscription`() {
        val url = "https://cdn.example.com/api/v1/client/sub/abc"
        assertEquals(
            HttpImportResolution.Subscription(url),
            ImportLinkClassifier.classifyParsedProxies(null, url),
        )
    }
}
