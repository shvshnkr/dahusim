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

    fun install(bodyByLink: Map<String, String>) {
        enabled = true
        this.bodyByLink = bodyByLink
    }

    fun clear() {
        enabled = false
        bodyByLink = null
    }

    internal fun bodyFor(canonicalLink: String): String? {
        if (!enabled) return null
        return bodyByLink?.get(canonicalLink)
    }
}
