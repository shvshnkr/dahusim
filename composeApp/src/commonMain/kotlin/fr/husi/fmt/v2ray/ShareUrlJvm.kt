package fr.husi.fmt.v2ray

import fr.husi.ktx.wrapIPV6Host
import java.io.ByteArrayOutputStream

/**
 * Pure-JVM mirror of libcore/url.go (Go net/url) for hosts without gojni.
 *
 * Parsing is lenient like Go url.Parse: the fragment is everything after the first '#',
 * the query ends at '#', the authority ends at the first '/'. Query values keep the
 * first occurrence per key (Go url.Values.Get) and treat '+' as space; fragment and
 * userinfo do not.
 */
class JvmShareUrl private constructor(
    private val schemePart: String,
    private val hostPart: String,
    private val portPart: String,
    private val userPart: String,
    private val fragmentPart: String,
    private val query: Map<String, String>,
) : ShareUrlReader {

    override val scheme: String get() = schemePart
    override val host: String get() = hostPart
    override val ports: String get() = portPart
    override val username: String get() = userPart
    override val fragment: String get() = fragmentPart

    override fun queryParameter(key: String): String = query[key] ?: ""

    override fun queryParameterUnescape(key: String): String {
        val value = query[key] ?: ""
        return try {
            unescape(value, plusToSpace = true)
        } catch (_: IllegalArgumentException) {
            value
        }
    }

    companion object {

        fun parse(link: String): JvmShareUrl {
            val schemeSeparator = link.indexOf("://")
            if (schemeSeparator <= 0) error("not an authority-based share link: $link")
            val scheme = link.substring(0, schemeSeparator)
            var rest = link.substring(schemeSeparator + 3)

            val rawFragment = rest.substringAfter('#', missingDelimiterValue = "")
            val fragment = if (rawFragment.isEmpty()) "" else unescape(rawFragment, plusToSpace = false)
            rest = rest.substringBefore('#')

            val query = parseQuery(rest.substringAfter('?', missingDelimiterValue = ""))
            rest = rest.substringBefore('?')

            val authority = rest.substringBefore('/')
            val lastAt = authority.lastIndexOf('@')
            val hostPort: String
            val user: String
            if (lastAt >= 0) {
                user = unescape(authority.substring(0, lastAt), plusToSpace = false)
                hostPort = authority.substring(lastAt + 1)
            } else {
                user = ""
                hostPort = authority
            }

            val host: String
            val port: String
            if (hostPort.startsWith("[")) {
                host = hostPort.substringAfter('[').substringBefore(']')
                port = hostPort.substringAfter(']', "").removePrefix(":")
            } else {
                val colon = hostPort.lastIndexOf(':')
                if (colon >= 0) {
                    host = hostPort.substring(0, colon)
                    port = hostPort.substring(colon + 1)
                } else {
                    host = hostPort
                    port = ""
                }
            }

            return JvmShareUrl(scheme, host, port, user, fragment, query)
        }

        private fun parseQuery(rawQuery: String): LinkedHashMap<String, String> {
            val result = LinkedHashMap<String, String>()
            if (rawQuery.isEmpty()) return result
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val key = try {
                    unescape(pair.substringBefore('='), plusToSpace = true)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                val value = try {
                    unescape(pair.substringAfter('=', ""), plusToSpace = true)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                if (!result.containsKey(key)) result[key] = value
            }
            return result
        }

        /** Go url.QueryUnescape: percent-decoding plus optional '+' to space. */
        fun unescape(s: String, plusToSpace: Boolean): String {
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                when (val c = s[i]) {
                    '%' -> {
                        if (i + 2 > s.length - 1) throw IllegalArgumentException("invalid URL escape")
                        val high = Character.digit(s[i + 1], 16)
                        val low = Character.digit(s[i + 2], 16)
                        if (high < 0 || low < 0) throw IllegalArgumentException("invalid URL escape")
                        bytes.write((high shl 4) or low)
                        i += 2
                    }

                    '+' -> if (plusToSpace) {
                        bytes.write(' '.code)
                    } else {
                        bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    }

                    else -> bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                }
                i++
            }
            return bytes.toString("UTF-8")
        }

        /** Conservative percent-encoding of everything outside the unreserved set. */
        fun escape(s: String, plusToSpace: Boolean): String {
            val out = StringBuilder(s.length)
            for (byte in s.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt().toChar()
                when {
                    c.isLetterOrDigit() && c.code < 0x80 || c == '-' || c == '_' || c == '.' || c == '~' -> out.append(c)
                    c == ' ' && plusToSpace -> out.append('+')
                    else -> out.append('%').append(byte.toHexString())
                }
            }
            return out.toString()
        }

        private fun Byte.toHexString(): String {
            val v = toInt() and 0xff
            return "0123456789ABCDEF"[v shr 4].toString() + "0123456789ABCDEF"[v and 0xf]
        }
    }
}

class JvmShareUrlBuilder(private val scheme: String) : ShareUrlBuilder {

    private var usernameValue: String = ""
    private var hostValue: String = ""
    private var portsValue: String = ""
    private var fragmentValue: String = ""
    private val query = LinkedHashMap<String, MutableList<String>>()

    override var username: String
        get() = usernameValue
        set(value) {
            usernameValue = value
        }

    override var host: String
        get() = hostValue
        set(value) {
            hostValue = value
        }

    override var ports: String
        get() = portsValue
        set(value) {
            portsValue = value
        }

    override var fragment: String
        get() = fragmentValue
        set(value) {
            fragmentValue = value
        }

    override fun addQueryParameter(key: String, value: String) {
        query.getOrPut(key) { mutableListOf() }.add(value)
    }

    override fun setQueryParameter(key: String, value: String) {
        query[key] = mutableListOf(value)
    }

    override fun build(): String {
        return StringBuilder().apply {
            append(scheme).append("://")
            if (usernameValue.isNotEmpty()) {
                append(JvmShareUrl.escape(usernameValue, plusToSpace = false)).append('@')
            }
            append(hostValue.wrapIPV6Host())
            if (portsValue.isNotEmpty()) append(':').append(portsValue)
            if (query.isNotEmpty()) {
                append('?')
                for ((key, values) in query.toSortedMap()) {
                    for (value in values) {
                        append(JvmShareUrl.escape(key, plusToSpace = true))
                        append('=')
                        append(JvmShareUrl.escape(value, plusToSpace = true))
                        append('&')
                    }
                }
                setLength(length - 1)
            }
            if (fragmentValue.isNotEmpty()) {
                append('#').append(JvmShareUrl.escape(fragmentValue, plusToSpace = false))
            }
        }.toString()
    }
}
