package fr.husi.routing

import fr.husi.database.DataStore
import fr.husi.libcore.Libcore
import fr.husi.utils.closeQuietly
import fr.husi.utils.simpleModeLog

/**
 * Detects VPN exit country via HTTP through the live tunnel (not device direct).
 * Uses global endpoints outside typical geosite-category-ru direct bypass.
 */
internal object VpnExitProbe {

    private const val MAX_BODY = 4096

    /** JSON with countryCode; not expected on RU geosite direct lists. */
    private const val IP_API_JSON = "http://ip-api.com/json/?fields=status,countryCode"

    /** Plain-text exit IP; global. */
    private val IP_TEXT_URLS = listOf(
        "https://ifconfig.me/ip",
        "https://api64.ipify.org",
    )

    fun clearCache() {
        DataStore.vpnExitIsRussia = null
        DataStore.vpnExitProbeProfileId = 0L
    }

    /**
     * @return true = exit in RU, false = not RU, null = probe failed
     */
    fun probeAndStore(profileId: Long, outboundTag: String, timeoutMs: Int): Boolean? {
        if (outboundTag.isBlank()) return null
        val client = Libcore.newClient(null)
        try {
            fetchCountryJson(client, outboundTag, IP_API_JSON, timeoutMs)?.let { code ->
                return storeResult(profileId, IP_API_JSON, code)
            }
            for (ipUrl in IP_TEXT_URLS) {
                val ip = fetchBody(client, outboundTag, ipUrl, timeoutMs)
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.trim()
                    ?.takeIf { it.count { c -> c == '.' } == 3 || it.contains(':') }
                    ?: continue
                val lookupUrl = "http://ip-api.com/json/$ip?fields=status,countryCode"
                fetchCountryJson(client, outboundTag, lookupUrl, timeoutMs)?.let { code ->
                    return storeResult(profileId, "$ipUrl→$lookupUrl", code)
                }
            }
        } finally {
            client.closeQuietly()
        }
        simpleModeLog(
            "SimpleMode",
            "H27 exit_probe_failed profileId=$profileId tagLen=${outboundTag.length}",
        )
        return null
    }

    private fun fetchCountryJson(
        client: fr.husi.libcore.Client,
        outboundTag: String,
        url: String,
        timeoutMs: Int,
    ): String? = parseCountryCode(fetchBody(client, outboundTag, url, timeoutMs))

    private fun fetchBody(
        client: fr.husi.libcore.Client,
        outboundTag: String,
        url: String,
        timeoutMs: Int,
    ): String? = runCatching {
        client.urlFetch(outboundTag, url, timeoutMs, MAX_BODY)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun storeResult(profileId: Long, via: String, countryCode: String): Boolean {
        val isRu = countryCode.equals("RU", ignoreCase = true)
        DataStore.vpnExitIsRussia = isRu
        DataStore.vpnExitProbeProfileId = profileId
        simpleModeLog(
            "SimpleMode",
            "H27 exit_probe profileId=$profileId via=$via country=$countryCode exitRu=$isRu",
        )
        return isRu
    }

    private fun parseCountryCode(body: String?): String? {
        if (body.isNullOrBlank()) return null
        Regex(""""countryCode"\s*:\s*"([A-Za-z]{2})"""")
            .find(body)?.groupValues?.get(1)?.let { return it }
        Regex(""""country_code"\s*:\s*"([A-Za-z]{2})"""")
            .find(body)?.groupValues?.get(1)?.let { return it }
        return null
    }
}
