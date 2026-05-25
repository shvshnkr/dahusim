package fr.husi.ui

import fr.husi.database.SubscriptionBean
import fr.husi.group.RawUpdater
import fr.husi.group.SubscriptionFetchProfile
import fr.husi.group.SubscriptionHttpFetch
import fr.husi.group.SubscriptionSourceKind
import fr.husi.group.SubscriptionUserAgentPresets
import fr.husi.ktx.SubscriptionFoundException
import fr.husi.ktx.parseProxies
import java.net.URL

/**
 * Resolves whether an HTTP(S) link is a subscription feed or a one-off server list.
 * Standalone nodes land in a user [fr.husi.GroupType.BASIC] group (created or matched by name).
 */
object ImportLinkClassifier {

    private val SUBSCRIPTION_PATH_HINTS = listOf(
        "/sub",
        "/subscription",
        "/api/v1/client",
        "/link/",
    )

    sealed interface HttpImportResolution {
        data class Subscription(val url: String) : HttpImportResolution
        data class Standalone(
            val proxies: List<fr.husi.fmt.AbstractBean>,
            val suggestedGroupName: String?,
        ) : HttpImportResolution

        data object Ambiguous : HttpImportResolution
    }

    fun looksLikeSubscriptionUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.startsWith("sing-box://") || trimmed.startsWith("husi://subscription")) {
            return true
        }
        val scheme = trimmed.substringBefore("://", "").lowercase()
        if (scheme !in setOf("http", "https")) return false
        val lower = trimmed.lowercase()
        if (SUBSCRIPTION_PATH_HINTS.any { lower.contains(it) }) return true
        if (SubscriptionSourceKind.inferFromLink(trimmed) == SubscriptionSourceKind.GITHUB) {
            return true
        }
        val path = runCatching { URL(trimmed).path.lowercase() }.getOrNull().orEmpty()
        return path.endsWith(".txt") ||
            path.endsWith(".yaml") ||
            path.endsWith(".yml") ||
            path.endsWith(".conf") ||
            path.contains("subscription")
    }

    fun suggestImportGroupName(url: String): String? {
        val parsed = runCatching { URL(url.trim()) }.getOrNull() ?: return null
        val fromPath = parsed.path.substringAfterLast('/')
            .removeSuffix(".txt")
            .removeSuffix(".yaml")
            .removeSuffix(".yml")
            .removeSuffix(".conf")
            .trim()
        if (fromPath.isNotBlank() && fromPath.length <= 48) return fromPath
        val host = parsed.host.substringBefore('.').trim()
        return host.takeIf { it.isNotBlank() }
    }

    suspend fun resolveHttpImport(url: String): HttpImportResolution {
        val trimmed = url.trim()
        if (!looksLikeSubscriptionUrl(trimmed)) {
            return tryParseInline(trimmed)
        }
        return try {
            val body = fetchHttpBody(trimmed)
            classifyFetchedBody(body, trimmed)
        } catch (_: Exception) {
            if (SubscriptionSourceKind.inferFromLink(trimmed) == SubscriptionSourceKind.GITHUB) {
                HttpImportResolution.Subscription(trimmed)
            } else {
                HttpImportResolution.Ambiguous
            }
        }
    }

    private suspend fun tryParseInline(text: String): HttpImportResolution {
        return try {
            val proxies = parseProxies(text)
            when {
                proxies.isEmpty() -> HttpImportResolution.Ambiguous
                proxies.size == 1 -> HttpImportResolution.Standalone(
                    proxies = proxies,
                    suggestedGroupName = suggestImportGroupName(text),
                )
                else -> HttpImportResolution.Subscription(text)
            }
        } catch (e: SubscriptionFoundException) {
            HttpImportResolution.Subscription(e.link)
        } catch (_: Exception) {
            HttpImportResolution.Ambiguous
        }
    }

    internal suspend fun classifyFetchedBody(body: String, sourceUrl: String): HttpImportResolution {
        return try {
            classifyParsedProxies(RawUpdater.parseRaw(body), sourceUrl)
        } catch (e: SubscriptionFoundException) {
            HttpImportResolution.Subscription(e.link)
        } catch (_: Exception) {
            classifyParsedProxies(null, sourceUrl)
        }
    }

    internal fun classifyParsedProxies(
        proxies: List<fr.husi.fmt.AbstractBean>?,
        sourceUrl: String,
    ): HttpImportResolution = when {
        proxies.isNullOrEmpty() -> {
            if (looksLikeSubscriptionUrl(sourceUrl)) {
                HttpImportResolution.Subscription(sourceUrl)
            } else {
                HttpImportResolution.Ambiguous
            }
        }
        proxies.size == 1 -> HttpImportResolution.Standalone(
            proxies = proxies,
            suggestedGroupName = suggestImportGroupName(sourceUrl),
        )
        else -> HttpImportResolution.Subscription(sourceUrl)
    }

    private suspend fun fetchHttpBody(link: String): String {
        val subscription = SubscriptionBean().apply {
            this.link = link
            fetchProfile = SubscriptionUserAgentPresets.inferFetchProfileForNewLink(link)
        }
        return SubscriptionHttpFetch.fetchText(
            SubscriptionHttpFetch.Request(
                canonicalLink = link,
                userAgent = SubscriptionFetchProfile.resolveUserAgent(subscription),
                purpose = SubscriptionHttpFetch.FetchPurpose.ImportPreview,
            ),
        ).body
    }
}
