package fr.husi.fmt.v2ray

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShareUrlJvmTest {

    private val vlessGrpcUrl =
        "vless://uuid@example.com:443?type=grpc&security=tls&sni=sni.example.com&serviceName=/grpc&flow=xtls-rprx-vision#test-vless"

    @Test
    fun `parse should read authority fragment and query`() {
        val url = JvmShareUrl.parse(vlessGrpcUrl)

        assertEquals("vless", url.scheme)
        assertEquals("example.com", url.host)
        assertEquals("443", url.ports)
        assertEquals("uuid", url.username)
        assertEquals("test-vless", url.fragment)
        assertEquals("grpc", url.queryParameter("type"))
        assertEquals("/grpc", url.queryParameter("serviceName"))
        assertEquals("xtls-rprx-vision", url.queryParameter("flow"))
        assertEquals("", url.queryParameter("missing"))
    }

    @Test
    fun `parse should unwrap ipv6 host`() {
        val url = JvmShareUrl.parse("vless://uuid@[2001:db8::1]:8443#v6")

        assertEquals("2001:db8::1", url.host)
        assertEquals("8443", url.ports)
        assertEquals("v6", url.fragment)
    }

    @Test
    fun `parse should keep first query value and decode plus only in query`() {
        val url = JvmShareUrl.parse("vless://u@example.com:1?a=one&a=two&b=a%2Bb&b=x%20y#Name+Here")

        assertEquals("one", url.queryParameter("a"))
        assertEquals("a+b", url.queryParameter("b"))
        assertEquals("a b", url.queryParameterUnescape("b"))
        assertEquals("Name+Here", url.fragment)
    }

    @Test
    fun `queryParameterUnescape should apply extra unescape pass`() {
        val url = JvmShareUrl.parse("vless://u@example.com:1?serviceName=%252Fgrpc#n")

        assertEquals("%2Fgrpc", url.queryParameter("serviceName"))
        assertEquals("/grpc", url.queryParameterUnescape("serviceName"))
    }

    @Test
    fun `parse should skip malformed escape pairs`() {
        val url = JvmShareUrl.parse("vless://u@example.com:1?ok=1&bad=%ZZ&more=2#n")

        assertEquals("1", url.queryParameter("ok"))
        assertEquals("2", url.queryParameter("more"))
    }

    @Test
    fun `parse should reject non authority links`() {
        assertFailsWith<IllegalStateException> {
            JvmShareUrl.parse("vmess:base64payload")
        }
    }

    @Test
    fun `build should sort query keys and round trip through parse`() {
        val source = JvmShareUrl.parse(vlessGrpcUrl)
        val built = newShareUrlBuilderForTest(source)
        val reparsed = JvmShareUrl.parse(built)

        assertEquals("vless://uuid@example.com:443", built.substringBefore('?'))
        assertTrue(built.indexOf("flow=") < built.indexOf("serviceName="), "query keys must be sorted: $built")
        assertEquals(source.host, reparsed.host)
        assertEquals(source.ports, reparsed.ports)
        assertEquals(source.username, reparsed.username)
        assertEquals(source.fragment, reparsed.fragment)
        assertEquals(source.queryParameter("type"), reparsed.queryParameter("type"))
        assertEquals(source.queryParameter("serviceName"), reparsed.queryParameter("serviceName"))
    }

    @Test
    fun `setQueryParameter should replace added values`() {
        val builder = JvmShareUrlBuilder("http")
        builder.addQueryParameter("type", "http")
        builder.setQueryParameter("type", "tcp")
        builder.addQueryParameter("headerType", "http")

        val link = builder.build()

        assertEquals("http://?headerType=http&type=tcp", link)
    }

    @Test
    fun `build should wrap ipv6 host and keep fragment`() {
        val builder = JvmShareUrlBuilder("vless")
        builder.username = "uuid"
        builder.host = "2001:db8::1"
        builder.ports = "443"
        builder.fragment = "name with space"

        val link = builder.build()
        val parsed = JvmShareUrl.parse(link)

        assertTrue(link.startsWith("vless://uuid@[2001:db8::1]:443"), link)
        assertEquals("2001:db8::1", parsed.host)
        assertEquals("name with space", parsed.fragment)
    }

    private fun newShareUrlBuilderForTest(source: ShareUrlReader): String {
        val builder = JvmShareUrlBuilder(source.scheme)
        builder.username = source.username
        builder.host = source.host
        builder.ports = source.ports
        builder.fragment = source.fragment
        for (key in listOf("type", "security", "sni", "serviceName", "flow")) {
            val value = source.queryParameter(key)
            if (value.isNotEmpty()) builder.addQueryParameter(key, value)
        }
        return builder.build()
    }
}
