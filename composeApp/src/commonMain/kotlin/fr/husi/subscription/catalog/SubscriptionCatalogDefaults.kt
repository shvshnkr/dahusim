package fr.husi.subscription.catalog

import fr.husi.database.ConnectPoolRole

object SubscriptionCatalogDefaults {

    const val GITHUB_SOURCE_PREFIX = "gh."
    const val BUILTIN_SOURCE_PREFIX = "builtin."
    const val RESERVED_BUILTIN_SOURCE_KEY = "reserved"
    const val RESERVED_BUILTIN_GROUP_NAME = "Built-in"

    /**
     * Joins mirror URLs of one source into a single link field (whitespace-separated —
     * the catalog record format is `|`-delimited, so `|` cannot be the mirror separator;
     * [fr.husi.group.SubscriptionLinks.splitMirrors] accepts both at fetch time).
     */
    private fun joinLinks(vararg urls: String): String = urls.joinToString(" ")

    data class CatalogSeed(
        val sourceKey: String,
        val name: String,
        val link: String,
        val poolRole: Int = ConnectPoolRole.ANY,
        val legacyLinks: Set<String> = emptySet(),
    )

    val STARTER_SEEDS: List<CatalogSeed> = listOf(
        CatalogSeed(
            "vlessfo-open",
            "Vlessforu",
            "https://sub.vlessfo.ru/vlessforu/working_configs.txt",
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "aetris-blacklist",
            "AetrisVPN BlackList",
            "https://raw.githubusercontent.com/flaafix/AetrisVPN-black-list/refs/heads/main/configs.txt",
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "aetris-wl-lite",
            "AetrisVPN WhiteList Lite",
            "https://raw.githubusercontent.com/flaafix/AetrisVPN-white-list-lite/refs/heads/main/AetrisVPN.txt",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "kizyak-beta6-bl",
            "КIЗЯК VPN BLACKLIST",
            joinLinks(
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta6BL.txt",
                "https://fastly.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta6BL.txt",
                "https://cdn.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta6BL.txt",
            ),
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            sourceKey = "kizyak-beta7",
            name = "КIЗЯК VPN 7",
            link = joinLinks(
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta7.txt",
                "https://fastly.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta7.txt",
                "https://cdn.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyakbeta7.txt",
            ),
            poolRole = ConnectPoolRole.WL,
            legacyLinks = setOf(
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta6.txt",
            ),
        ),
        CatalogSeed(
            sourceKey = "zieng2-wl",
            name = "zieng2 wl",
            link = joinLinks(
                "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
                "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
                "https://bitbucket.org/zieng2/wl/raw/main/vless_universal.txt",
                "https://codeberg.org/zieng2/wl/raw/branch/main/vless_universal.txt",
            ),
            poolRole = ConnectPoolRole.WL,
            legacyLinks = setOf(
                "https://hub.mos.ru/zieng2/wl/raw/main/list_universal.txt",
            ),
        ),
    )

    fun builtinSourceId(sourceKey: String): String = "$BUILTIN_SOURCE_PREFIX$sourceKey"

    fun reservedBuiltinSourceId(): String = builtinSourceId(RESERVED_BUILTIN_SOURCE_KEY)

    fun poolRoleToken(role: Int): String = when (role) {
        ConnectPoolRole.WL -> "wl"
        ConnectPoolRole.OPEN -> "open"
        else -> "any"
    }

    fun joinToCatalogLines(generation: Long): String = buildString {
        appendLine("HUSI_SUBSCRIPTION_CATALOG_V1")
        appendLine("generation=$generation")
        appendLine("allow_empty=false")
        for (seed in STARTER_SEEDS) {
            append("UPSERT|${seed.sourceKey}|${seed.name}|${seed.link}|RAW|default|")
            appendLine(poolRoleToken(seed.poolRole))
        }
    }
}
