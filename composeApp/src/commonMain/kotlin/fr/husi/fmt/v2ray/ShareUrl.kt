package fr.husi.fmt.v2ray

import fr.husi.ktx.blankAsNull
import fr.husi.libcore.Libcore
import fr.husi.libcore.URL

/**
 * Read view over a share link, mirroring libcore/url.go (Go net/url) semantics:
 * `queryParameter` returns the decoded first value or "", `queryParameterUnescape`
 * applies one extra QueryUnescape pass.
 *
 * Native libcore (gojni) is not loadable on desktop JVM hosts without the packaged
 * native library, so parsers dispatch to the pure-JVM implementation in ShareUrlJvm.kt
 * when [useLibcoreShareUrl] is false.
 */
interface ShareUrlReader {
    val scheme: String
    val host: String
    val ports: String
    val username: String
    val fragment: String

    fun queryParameter(key: String): String

    fun queryParameterUnescape(key: String): String

    fun queryParameterNotBlank(key: String): String? = queryParameter(key).blankAsNull()

    fun queryParameterUnescapeNotBlank(key: String): String? = queryParameterUnescape(key).blankAsNull()

    fun parseBoolean(key: String): Boolean = when (queryParameter(key).lowercase()) {
        "1", "true", "yes" -> true
        else -> false
    }
}

/**
 * Builder view mirroring libcore/url.go: insertion-ordered values, `setQueryParameter`
 * replaces previous values, [build] emits keys sorted like Go url.Values.Encode.
 */
interface ShareUrlBuilder {
    var username: String
    var host: String
    var ports: String
    var fragment: String

    fun addQueryParameter(key: String, value: String)

    fun setQueryParameter(key: String, value: String)

    fun build(): String
}

/** True when native libcore is loadable (Android, desktop hosts with gojni). */
val useLibcoreShareUrl: Boolean by lazy {
    runCatching { Libcore.parseURL("vless://probe@127.0.0.1:1") }.isSuccess
}

fun parseShareUrl(link: String): ShareUrlReader =
    if (useLibcoreShareUrl) {
        LibcoreShareUrlReader(Libcore.parseURL(link))
    } else {
        JvmShareUrl.parse(link)
    }

fun newShareUrlBuilder(scheme: String): ShareUrlBuilder =
    if (useLibcoreShareUrl) {
        LibcoreShareUrlBuilder(Libcore.newURL(scheme))
    } else {
        JvmShareUrlBuilder(scheme)
    }

class LibcoreShareUrlReader(private val url: URL) : ShareUrlReader {
    override val scheme: String = url.scheme
    override val host: String = url.host
    override val ports: String = url.ports
    override val username: String = url.username
    override val fragment: String = url.fragment

    override fun queryParameter(key: String): String = url.queryParameter(key)

    override fun queryParameterUnescape(key: String): String = url.queryParameterUnescape(key)
}

class LibcoreShareUrlBuilder(private val url: URL) : ShareUrlBuilder {
    override var username: String
        get() = url.username
        set(value) {
            url.username = value
        }

    override var host: String
        get() = url.host
        set(value) {
            url.host = value
        }

    override var ports: String
        get() = url.ports
        set(value) {
            url.ports = value
        }

    override var fragment: String
        get() = url.fragment
        set(value) {
            url.fragment = value
        }

    override fun addQueryParameter(key: String, value: String) {
        url.addQueryParameter(key, value)
    }

    override fun setQueryParameter(key: String, value: String) {
        url.setQueryParameter(key, value)
    }

    override fun build(): String = url.string
}
