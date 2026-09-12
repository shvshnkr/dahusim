package fr.husi.group

/**
 * Subscription link with optional mirrors: a single link field may carry several
 * candidate URLs separated by `|` (v2rayNG convention) or whitespace (catalog-safe,
 * since catalog records are `|`-delimited). The first candidate is the canonical link.
 */
object SubscriptionLinks {

    private val SEPARATORS = charArrayOf('|', ' ', '\t', '\n', '\r')

    private val ALLOWED_SCHEMES = listOf("https://", "http://", "content://")

    fun splitMirrors(link: String): List<String> = link.split(*SEPARATORS)
        .map { it.trim() }
        .filter { candidate -> ALLOWED_SCHEMES.any { candidate.startsWith(it) } }
        .distinct()

    fun firstLink(link: String): String = splitMirrors(link).firstOrNull() ?: link.trim()
}
