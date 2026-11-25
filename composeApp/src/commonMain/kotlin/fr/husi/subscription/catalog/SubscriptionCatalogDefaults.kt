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
            "rkp-bl",
            "RKP BlackList",
            joinLinks(
                "https://hub.mos.ru/rkp/sub-roskompozor/raw/main/bl",
                "https://raw.githubusercontent.com/rkp-community/sub/main/bl",
            ),
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "rkp-wl",
            "RKP WhiteList",
            joinLinks(
                "https://hub.mos.ru/rkp/sub-roskompozor/raw/main/wl",
                "https://raw.githubusercontent.com/rkp-community/sub/main/wl",
            ),
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
            sourceKey = "zieng2-wl",
            name = "zieng2 wl",
            link = joinLinks(
                "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
                "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
                "https://bitbucket.org/zieng2/wl/raw/main/vless_universal.txt",
            ),
            poolRole = ConnectPoolRole.WL,
            legacyLinks = setOf(
                "https://hub.mos.ru/zieng2/wl/raw/main/list_universal.txt",
                "https://codeberg.org/zieng2/wl/raw/branch/main/vless_universal.txt",
            ),
        ),
        CatalogSeed(
            "akres-all",
            "AkresVPN",
            "https://raw.githubusercontent.com/theakres/VPN/refs/heads/main/all",
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "pizduk-wl",
            "PizdukVPN WhiteList",
            joinLinks(
                "https://raw.githubusercontent.com/PizdukMod/PizdukVPN/main/wl",
                "https://fastly.jsdelivr.net/gh/PizdukMod/PizdukVPN@main/wl",
            ),
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "white-cidr-ru",
            "WHITE-CIDR-RU",
            joinLinks(
                "https://bitbucket.org/igareck/vpn-configs-for-russia/raw/main/WHITE-CIDR-RU-all.txt",
                "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt",
            ),
            ConnectPoolRole.WL,
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
