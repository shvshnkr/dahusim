package fr.husi.fmt.trojan

import fr.husi.fmt.v2ray.parseDuckSoft
import fr.husi.fmt.v2ray.parseShareUrl

fun parseTrojan(link: String): TrojanBean {
    val url = parseShareUrl(link)
    return TrojanBean().apply {
        parseDuckSoft(url)
        url.parseBoolean("allowInsecure")
        url.queryParameterNotBlank("peer")?.let {
            sni = it
        }
    }

}
