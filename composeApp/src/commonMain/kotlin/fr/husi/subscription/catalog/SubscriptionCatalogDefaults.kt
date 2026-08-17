package fr.husi.subscription.catalog

import fr.husi.database.ConnectPoolRole

object SubscriptionCatalogDefaults {

    const val GITHUB_SOURCE_PREFIX = "gh."
    const val BUILTIN_SOURCE_PREFIX = "builtin."
    const val RESERVED_BUILTIN_SOURCE_KEY = "reserved"
    const val RESERVED_BUILTIN_GROUP_NAME = "Built-in"

    data class CatalogSeed(
        val sourceKey: String,
        val name: String,
        val link: String,
        val poolRole: Int = ConnectPoolRole.ANY,
        val legacyLinks: Set<String> = emptySet(),
    )

    val STARTER_SEEDS: List<CatalogSeed> = listOf(
        CatalogSeed(
            sourceKey = "swordware-main",
            name = "Swordware",
            link = "https://raw.githubusercontent.com/mbelspb-gif/gdffgd/refs/heads/main/Swordware.net",
            poolRole = ConnectPoolRole.OPEN,
            legacyLinks = setOf(
                "https://raw.githubusercontent.com/mbelspb-gif/dddddad/refs/heads/main/Swordware.txt",
                "https://raw.githubusercontent.com/mbelspb-gif/ffsfsfssdf/refs/heads/main/TG-swordware",
            ),
        ),
        CatalogSeed(
            "black-vless-rus-mobile",
            "BLACK VLESS RUS mobile",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS_mobile.txt",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "vless-wl-rus-mobile",
            "Vless Reality White Lists Rus Mobile",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "aetris-vpn",
            "AetrisVPN",
            "https://gist.githubusercontent.com/flaafix/c79a81037d15163360571c7a7331b153/raw/AetrisVPN.txt",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            sourceKey = "tri-228-open",
            name = "tri_228 open",
            link = "https://raw.githubusercontent.com/shvshnkr/dahusim/main/docs/subscription-feeds/tri_228-open.txt",
            poolRole = ConnectPoolRole.OPEN,
            legacyLinks = setOf(
                "https://gitverse.ru/api/repos/nzea234/ekaterina_nevilikaya228/raw/branch/master/tri_228.txt",
                "https://raw.githubusercontent.com/nzea243/ikoV31tud_vpn/refs/heads/main/tri_228.txt",
            ),
        ),
        CatalogSeed(
            sourceKey = "tri-228-wl",
            name = "tri_228 wl",
            link = "https://raw.githubusercontent.com/shvshnkr/dahusim/main/docs/subscription-feeds/tri_228-wl.txt",
            poolRole = ConnectPoolRole.WL,
        ),
        CatalogSeed(
            sourceKey = "zieng2-wl",
            name = "zieng2 wl",
            link = "https://hub.mos.ru/zieng2/wl/raw/main/list_universal.txt",
            poolRole = ConnectPoolRole.WL,
        ),
        CatalogSeed(
            sourceKey = "wl-standalone",
            name = "WL standalone",
            link = "https://raw.githubusercontent.com/shvshnkr/dahusim/main/docs/subscription-feeds/wl-standalone.txt",
            poolRole = ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "white-lattice",
            "WhiteLattice",
            "https://raw.githubusercontent.com/HikaruApps/WhiteLattice/refs/heads/main/subscriptions/config.txt",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "white-list-vpn-black",
            "WhiteListVpn BlackList",
            "https://raw.githubusercontent.com/SilentGhostCodes/WhiteListVpn/refs/heads/main/BlackList.txt",
            ConnectPoolRole.WL,
        ),
        CatalogSeed("wlrus-blackl", "wlrus blackl", "https://wlrus.lol/confs/blackl.txt", ConnectPoolRole.WL),
        CatalogSeed(
            "migiti-wl",
            "MiGiTi WL",
            "https://raw.githubusercontent.com/misha12333211-ctrl/proxy-subs/refs/heads/main/1.txt",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "vlessfo-open",
            "Vlessforu",
            "https://sub.vlessfo.ru/vlessforu/working_configs.txt",
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "razlo4ka-open",
            "Razlo4ka",
            "https://raw.githubusercontent.com/free1zona/Keyfreetee/refs/heads/main/razlo4ka7",
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
            "kizyak-beta6",
            "КIЗЯК VPN 6",
            "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta6.txt",
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "kizyak-beta6-bl",
            "КIЗЯК VPN BLACKLIST",
            "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta6BL.txt",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "kizyak-testru",
            "КIЗЯК VPN TEST_RU",
            "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyaktestru.txt",
            ConnectPoolRole.OPEN,
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
