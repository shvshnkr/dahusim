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
            joinLinks(
                "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS_mobile.txt",
                "https://gitlab.com/igareck/vpn-configs-for-russia/raw/main/BLACK_VLESS_RUS_mobile.txt",
                "https://codeberg.org/igareck/vpn-configs-for-russia/raw/branch/main/BLACK_VLESS_RUS_mobile.txt",
                "https://raw.githack.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
            ),
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "vless-wl-rus-mobile",
            "Vless Reality White Lists Rus Mobile",
            joinLinks(
                "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
                "https://gitlab.com/igareck/vpn-configs-for-russia/raw/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
                "https://codeberg.org/igareck/vpn-configs-for-russia/raw/branch/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
                "https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            ),
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
            "kizyak-testru",
            "КIЗЯК VPN TEST_RU",
            joinLinks(
                "https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyaktestru.txt",
                "https://fastly.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyaktestru.txt",
                "https://cdn.jsdelivr.net/gh/Maskkost93/kizyak-vpn-4.0@main/kizyaktestru.txt",
            ),
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "terik21-hiddify",
            "HiddifySubs VlessKeys",
            "https://raw.githubusercontent.com/terik21/HiddifySubs-VlessKeys/refs/heads/main/main",
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "kafka-def",
            "Kafka sub",
            "https://raw.githubusercontent.com/kafka-def/kafka-sub/refs/heads/main/subscription.txt",
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "migiti-fast-ru",
            "MiGiTi FAST RU SNI",
            "https://raw.githubusercontent.com/misha12333211-ctrl/proxy-subs/refs/heads/main/2.txt",
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "go-engine",
            "Go Engine By MiGiTi",
            "https://flat-hill-2415.fatago78.workers.dev",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "haven-gitverse",
            "Haven VPN",
            "https://gitverse.ru/api/repos/soulcoil/Haven/raw/branch/master/Haven",
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "hardvpn-wlall",
            "HARDVPN Белые списки ALL",
            "https://raw.githubusercontent.com/ksenkovsolo/HardVPN-bypass-WhiteLists-/refs/heads/main/vpn-lte/WHITELIST-ALL.txt",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "goida-open",
            "goida Обход чёрных",
            joinLinks(
                "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/1.txt",
                "https://fastly.jsdelivr.net/gh/AvenCores/goida-vpn-configs@main/githubmirror/1.txt",
                "https://cdn.jsdelivr.net/gh/AvenCores/goida-vpn-configs@main/githubmirror/1.txt",
            ),
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "goida-wl",
            "goida Обход белых SNI/CIDR",
            joinLinks(
                "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/26.txt",
                "https://fastly.jsdelivr.net/gh/AvenCores/goida-vpn-configs@main/githubmirror/26.txt",
                "https://cdn.jsdelivr.net/gh/AvenCores/goida-vpn-configs@main/githubmirror/26.txt",
            ),
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "rkp-wl",
            "RKP Белые списки",
            joinLinks(
                "https://raw.githubusercontent.com/RKPchannel/RKP_bypass_configs/main/whitelist.txt",
                "https://fastly.jsdelivr.net/gh/RKPchannel/RKP_bypass_configs@main/whitelist.txt",
                "https://cdn.jsdelivr.net/gh/RKPchannel/RKP_bypass_configs@main/whitelist.txt",
            ),
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "rkp-black",
            "RKP Чёрные списки",
            joinLinks(
                "https://raw.githubusercontent.com/RKPchannel/RKP_bypass_configs/main/blacklist.txt",
                "https://fastly.jsdelivr.net/gh/RKPchannel/RKP_bypass_configs@main/blacklist.txt",
                "https://cdn.jsdelivr.net/gh/RKPchannel/RKP_bypass_configs@main/blacklist.txt",
            ),
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "hiztin-gribi",
            "VLESS-PO-GRIBI Обход",
            joinLinks(
                "https://raw.githubusercontent.com/hiztin/VLESS-PO-GRIBI/main/deploy/subscriptions/25.txt",
                "https://fastly.jsdelivr.net/gh/hiztin/VLESS-PO-GRIBI@main/deploy/subscriptions/25.txt",
                "https://cdn.jsdelivr.net/gh/hiztin/VLESS-PO-GRIBI@main/deploy/subscriptions/25.txt",
            ),
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "etoneya-wl",
            "ЭтоНеЯ Белые списки",
            joinLinks(
                "https://etoneya.su/whitelist",
                "https://etoneya.best/whitelist",
                "https://alley.serv00.net/whitelist",
                "https://internet-tenshi.kangel.tech/whitelist",
                "https://whitelist.etoneya.baby",
                "https://etoskam.ru/whitelist",
                "https://xn--e1apcp8cq.xn--p1ai/whitelist",
            ),
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "etoneya-other",
            "ЭтоНеЯ Прочие (чёрные)",
            joinLinks(
                "https://etoneya.su/other",
                "https://etoneya.best/other",
                "https://alley.serv00.net/other",
                "https://internet-tenshi.kangel.tech/other",
                "https://blacklist.etoneya.baby",
                "https://etoskam.ru/other",
                "https://xn--e1apcp8cq.xn--p1ai/other",
            ),
            ConnectPoolRole.OPEN,
        ),
        CatalogSeed(
            "cyberportal-wl-list",
            "CYBERPORTAL White List",
            "https://warp-gen.cyb-portal.org/CP-035",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "cyberportal-cidr-wl",
            "CYBERPORTAL CIDR White",
            "https://warp-gen.cyb-portal.org/CP-006",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "cyberportal-sni-wl",
            "CYBERPORTAL SNI White",
            "https://warp-gen.cyb-portal.org/CP-008",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "cyberportal-wl-keys",
            "CYBERPORTAL White Keys",
            "https://warp-gen.cyb-portal.org/CP-042",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "jsxta-autotest",
            "jsxta Авто-тестируемые",
            "https://gbr.mydan.online/configs",
            ConnectPoolRole.WL,
        ),
        CatalogSeed(
            "mifa-reserve",
            "mifa/bobrik Резерв",
            "https://mifa.world/bobrik",
            ConnectPoolRole.ANY,
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
