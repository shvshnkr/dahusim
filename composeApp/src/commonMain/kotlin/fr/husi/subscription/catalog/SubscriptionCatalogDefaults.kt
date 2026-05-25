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
        CatalogSeed("mifa-main", "Mifa Main", "https://mifa.world/vless", ConnectPoolRole.OPEN),
        CatalogSeed("mifa-hysteria", "Mifa Hysteria", "https://mifa.world/hysteria", ConnectPoolRole.OPEN),
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
            "vless-wl-rus-mobile-2",
            "Vless Reality White Lists Rus Mobile 2",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile-2.txt",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "aetris-vpn",
            "AetrisVPN",
            "https://gist.githubusercontent.com/flaafix/c79a81037d15163360571c7a7331b153/raw/AetrisVPN.txt",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "tri-228",
            "tri_228",
            "https://raw.githubusercontent.com/nzea243/ikoV31tud_vpn/refs/heads/main/tri_228.txt",
            ConnectPoolRole.OPEN,
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
