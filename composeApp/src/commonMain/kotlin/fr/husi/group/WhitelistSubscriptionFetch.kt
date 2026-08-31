package fr.husi.group

import fr.husi.ktx.urlSafe
import java.net.URL

/**
 * Fetches blocked subscription URLs on whitelist-only networks via Yandex Translate,
 * then extracts share links from the translator HTML wrapper.
 */
object WhitelistSubscriptionFetch {

    private val DIRECT_BS_HOST_SUFFIXES = listOf(
        "gitverse.ru",
        "storage.yandexcloud.net",
    )

    private val MIRROR_HOST_SUFFIXES = listOf(
        "raw.githubusercontent.com",
        "gist.githubusercontent.com",
        "githubusercontent.com",
    )

    private val PROXY_URI_IN_TEXT = Regex(
        """(?i)(vless|vmess|trojan|ss|hysteria2?)://[^\s<"']+""",
    )

    fun shouldUseYandexMirror(
        link: String,
        whitelistRestricted: Boolean,
        vpnConnected: Boolean,
    ): Boolean {
        if (!whitelistRestricted || vpnConnected) return false
        if (link.contains("translate.yandex.", ignoreCase = true)) return false
        val host = linkHost(link) ?: return false
        if (host.isDirectWhitelistHost()) return false
        return host.needsYandexMirror()
    }

    fun yandexTranslateUrl(originalLink: String): String =
        "https://translate.yandex.ru/translate?url=${originalLink.urlSafe()}&lang=en-ru"

    /** True when [link] is hosted on a GitHub host the Yandex mirror can serve ([yandexTranslateUrl]). */
    internal fun supportsYandexMirror(link: String): Boolean {
        val host = linkHost(link) ?: return false
        return host.needsYandexMirror()
    }

    fun resolveFetchLink(
        link: String,
        whitelistRestricted: Boolean,
        vpnConnected: Boolean,
    ): String = if (shouldUseYandexMirror(link, whitelistRestricted, vpnConnected)) {
        yandexTranslateUrl(link)
    } else {
        link
    }

    /**
     * Plain subscription text passes through; Yandex/Google translate HTML is reduced to proxy URIs.
     */
    fun extractSubscriptionBody(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val looksHtml = trimmed.startsWith("<") ||
            trimmed.contains("<html", ignoreCase = true) ||
            trimmed.contains("<!DOCTYPE", ignoreCase = true)
        if (!looksHtml) {
            return unescapeHtmlEntities(trimmed)
        }
        val unescaped = unescapeHtmlEntities(trimmed)
        val uris = PROXY_URI_IN_TEXT.findAll(unescaped).map { it.value }.distinct().toList()
        if (uris.isNotEmpty()) {
            return uris.joinToString("\n")
        }
        return unescaped
            .replace(Regex("<[^>]+>"), "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun linkHost(link: String): String? = runCatching {
        URL(link).host.lowercase()
    }.getOrNull()

    private fun String.isDirectWhitelistHost(): Boolean =
        DIRECT_BS_HOST_SUFFIXES.any { suffix -> this == suffix || this.endsWith(".$suffix") }

    private fun String.needsYandexMirror(): Boolean =
        MIRROR_HOST_SUFFIXES.any { suffix -> this == suffix || this.endsWith(".$suffix") }

    private fun unescapeHtmlEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
