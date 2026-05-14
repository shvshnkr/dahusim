package fr.husi.bootstrap

import fr.husi.fmt.trojan.TrojanBean
import fr.husi.ktx.applyDefaultValues

/**
 * Public Trojan endpoints shared in community configs (same password across nodes).
 * Update if endpoints are revoked. Not a security boundary — credentials are in the APK.
 */
object WhitelistBuiltinProxies {

    const val GROUP_NAME: String = "Built-in (simple mode helpers)"

    private const val SHARED_PASSWORD: String = "Qfw0MqoyNkSvqjRhZ_x5WNM3V_tF6q"

    data class BuiltinTrojanDef(
        /** Stable profile name for idempotent DB sync */
        val profileName: String,
        val address: String,
        val port: Int,
        val sni: String,
        /** Comma-separated ALPN list for sing-box / StandardV2RayBean */
        val alpn: String,
        /** First four are used when the network is whitelist-only; fifth is also used when Google is OK */
        val useInWhitelistOnlyPool: Boolean,
    )

    val definitions: List<BuiltinTrojanDef> = listOf(
        BuiltinTrojanDef(
            profileName = "Simple helper PL #41",
            address = "87.239.104.97",
            port = 7443,
            sni = "plthree.rushtaxi.ru",
            alpn = "h2,http/1.1",
            useInWhitelistOnlyPool = true,
        ),
        BuiltinTrojanDef(
            profileName = "Simple helper PL #42",
            address = "109.120.190.146",
            port = 8443,
            sni = "pl.serverstats.ru",
            alpn = "h2,http/1.1",
            useInWhitelistOnlyPool = true,
        ),
        BuiltinTrojanDef(
            profileName = "Simple helper PL #43",
            address = "109.120.191.129",
            port = 8443,
            sni = "pltwo.rushtaxi.ru",
            alpn = "h2,http/1.1",
            useInWhitelistOnlyPool = true,
        ),
        BuiltinTrojanDef(
            profileName = "Simple helper PL #44",
            address = "79.174.95.188",
            port = 7443,
            sni = "pltwo.rushtaxi.ru",
            alpn = "h2,http/1.1",
            useInWhitelistOnlyPool = true,
        ),
        BuiltinTrojanDef(
            profileName = "Simple helper RU federal",
            address = "ru.federal-usa.com",
            port = 8443,
            sni = "ru.federal-usa.com",
            alpn = "h3,h2,http/1.1",
            useInWhitelistOnlyPool = false,
        ),
    )

    fun trojanBean(def: BuiltinTrojanDef): TrojanBean {
        return TrojanBean().apply {
            name = def.profileName
            serverAddress = def.address
            serverPort = def.port
            password = SHARED_PASSWORD
            security = "tls"
            sni = def.sni
            alpn = def.alpn
            utlsFingerprint = "qq"
            v2rayTransport = ""
            allowInsecure = false
        }.applyDefaultValues()
    }
}
