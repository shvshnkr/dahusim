package fr.husi.group

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
        // Mirror lists ("https://a https://b") are not a single URL — classify by canonical.
        val host = runCatching { URL(SubscriptionLinks.firstLink(link)).host.lowercase() }.getOrNull().orEmpty()
        return if (host in GITHUB_HOSTS) GITHUB else WEB
    }
}
