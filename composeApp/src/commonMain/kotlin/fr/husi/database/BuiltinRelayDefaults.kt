package fr.husi.database

import fr.husi.subscription.catalog.SubscriptionCatalogDefaults

/** App-shipped standalone relay profiles (basic pool, not subscription feeds). */
object BuiltinRelayDefaults {

    const val GROUP_NAME = "Built-in relay"
    const val STANDALONE_SE_SOURCE_KEY = "standalone-se"
    const val STANDALONE_SE_PROFILE_NAME = "SE relay"

    const val LEGACY_GROUP_NAME = "Quick standalone SE"
    const val LEGACY_PROFILE_NAME = "SE relay builtin"

    const val STANDALONE_SE_VLESS_URI: String =
        "vless://2001daf3-5c56-4bef-8ea6-8dd0493c5a4c@2.27.23.73:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.deepl.com&fp=chrome&pbk=ZHEMPjSWslk6_qD2JNQzd5enUPz8nY9mYRRuM6NkZmU&sid=1a&packetEncoding=xudp#%F0%9F%87%B8%F0%9F%87%AA%20SE%20%7C%20VLESS%20%7C%20%E2%9A%A1%201362ms"

    fun groupSourceId(): String = SubscriptionCatalogDefaults.builtinSourceId(STANDALONE_SE_SOURCE_KEY)

    fun profileSourceId(): String = "${groupSourceId()}.profile"
}
