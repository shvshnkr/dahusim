package fr.husi.group

import fr.husi.database.SubscriptionBean
import fr.husi.ktx.USER_AGENT
import fr.husi.ktx.generateUserAgent
import java.net.URL

object SubscriptionSourceKind {
    const val GITHUB = 0
    const val WEB = 1

    private val GITHUB_HOSTS = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "gist.githubusercontent.com",
    )

    fun inferFromLink(link: String): Int {
        val host = runCatching { URL(link).host.lowercase() }.getOrNull().orEmpty()
        return if (host in GITHUB_HOSTS) GITHUB else WEB
    }
}

object SubscriptionFetchProfile {
    const val DEFAULT = 0
    const val HAPP = 1
    const val CUSTOM = 2

    private const val HAPP_USER_AGENT = "happ/2.9.0"

    fun resolveUserAgent(subscription: SubscriptionBean): String {
        return when (subscription.fetchProfile) {
            HAPP -> HAPP_USER_AGENT
            CUSTOM -> generateUserAgent(subscription.customUserAgent)
            DEFAULT -> USER_AGENT
            else -> generateUserAgent(subscription.customUserAgent)
        }
    }
}
