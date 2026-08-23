package fr.husi.group

/**
 * Injects canned subscription HTTP bodies during journey / scenario tests.
 * Inert in production ([enabled] is false and [bodyByLink] is null).
 */
object SubscriptionFetchTestHooks {
    var enabled: Boolean = false
        private set

    var bodyByLink: Map<String, String>? = null
        private set

    var failForFetchLinks: Set<String>? = null
        private set

    /** Last HTTP timeout (ms) applied by [fr.husi.group.SubscriptionHttpFetch] while [enabled]. */
    var lastTimeoutMs: Int? = null
        internal set

    fun install(bodyByLink: Map<String, String>, failForFetchLinks: Set<String> = emptySet()) {
        enabled = true
        this.bodyByLink = bodyByLink
        this.failForFetchLinks = failForFetchLinks
    }

    fun clear() {
        enabled = false
        bodyByLink = null
        failForFetchLinks = null
        lastTimeoutMs = null
    }

    internal fun bodyFor(canonicalLink: String): String? {
        if (!enabled) return null
        return bodyByLink?.get(canonicalLink)
    }

    internal fun shouldFailFetch(fetchLink: String): Boolean {
        if (!enabled) return false
        return failForFetchLinks?.contains(fetchLink) == true
    }
}
