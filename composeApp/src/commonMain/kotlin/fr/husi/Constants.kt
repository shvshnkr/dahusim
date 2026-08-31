package fr.husi

const val CONNECTION_TEST_URL = "http://cp.cloudflare.com/"
const val SPEED_TEST_URL = "http://speed.cloudflare.com/__down?bytes=20000000"
const val SPEED_TEST_UPLOAD_URL = "http://speed.cloudflare.com/__up"

object Key {

    const val DB_PUBLIC = "configuration.db"
    const val DB_PROFILE = "sager_net.db"

    const val GENERAL_SETTINGS = "generalSettings"
    const val ROUTE_SETTINGS = "routeSettings"
    const val PROTOCOL_SETTINGS = "protocolSettings"
    const val DNS_SETTINGS = "dnsSettings"
    const val INBOUND_SETTINGS = "inboundSettings"
    const val MISC_SETTINGS = "miscSettings"
    const val APP_UPDATE_SETTINGS = "appUpdateSettings"
    const val SUBSCRIPTION_CATALOG_SETTINGS = "subscriptionCatalogSettings"
    const val PROBE_2K_SETTINGS = "probe2kSettings"
    const val NTP_SETTINGS = "ntpSettings"

    const val PERSIST_ACROSS_REBOOT = "isAutoConnect"

    const val APP_EXPERT = "isExpert"
    const val APP_THEME = "appTheme"
    const val NIGHT_THEME = "nightTheme"
    const val APP_LANGUAGE = "appLanguage"
    const val SERVICE_MODE = "serviceMode"
    const val MODE_VPN = "vpn"
    const val MODE_PROXY = "proxy"
    const val DEBUG_LISTEN = "debugListen"
    const val NETWORK_STRATEGY = "networkStrategy"

    const val REMOTE_DNS = "remoteDns"
    const val DIRECT_DNS = "directDns"
    const val DOMAIN_STRATEGY_FOR_DIRECT = "domain_strategy_for_direct"
    const val DOMAIN_STRATEGY_FOR_SERVER = "domain_strategy_for_server"
    const val ENABLE_FAKE_DNS = "enableFakeDns"
    const val FAKE_DNS_FOR_ALL = "fakeDNSForAll"
    const val FAKE_DNS_RANGE_4 = "fakeDNSRange4"
    const val FAKE_DNS_RANGE_6 = "fakeDNSRange6"
    const val DNS_HOSTS = "dnsHosts"
    const val DNS_OPTIMISTIC_CACHE = "dnsOptimisticCache"

    const val PROXY_APPS = "proxyApps"
    const val UPDATE_PROXY_APPS_WHEN_INSTALL = "updateProxyAppsWhenInstall"
    const val BYPASS_MODE = "bypassMode"

    // const val INDIVIDUAL = "individual"
    const val PACKAGES = "packages"
    const val METERED_NETWORK = "meteredNetwork"
    const val NETWORK_INTERFACE_STRATEGY = "networkInterfaceStrategy"
    const val NETWORK_PREFERRED_INTERFACES = "networkPreferredInterfaces"

    // const val FORCED_SEARCH_PROCESS = "forcedSearchProcess"
    const val RULES_PROVIDER = "rulesProvider"
    const val ROUTE_QUICK_PROFILE = "routeQuickProfile"
    const val CUSTOM_RULE_PROVIDER = "customRuleProvider"
    const val ROUTE_ASSETS_AUTO_UPDATE_DELAY = "routeAssetsAutoUpdateDelay"
    const val ROUTE_ASSETS_LAST_UPDATED = "routeAssetsLastUpdated"

    const val BYPASS_LAN = "bypassLan"

    const val APPEND_HTTP_PROXY = "appendHttpProxy"
    const val HTTP_PROXY_BYPASS = "httpProxyBypass"
    const val INBOUND_USERNAME = "inboundUsername"
    const val INBOUND_PASSWORD = "inboundPassword"
    const val INBOUND_AUTO_CREDENTIALS = "inboundAutoCredentials"
    const val ANCHOR_SSID = "anchorSSID"

    const val MIXED_PORT = "mixedPort"
    const val ALLOW_ACCESS = "allowAccess"
    const val SHOW_GROUP_IN_NOTIFICATION = "showGroupInNotification"
    const val SPEED_INTERVAL = "speedInterval"
    const val SHOW_DIRECT_SPEED = "showDirectSpeed"
    const val LOCAL_DNS_PORT = "portLocalDns"

    const val CONNECTION_TEST_URL = "connectionTestURL"
    const val CONNECTION_TEST_CONCURRENT = "connectionTestConcurrent"
    const val CONNECTION_TEST_TIMEOUT = "connectionTestTimeout"

    const val SECURITY_ADVISORY = "securityAdvisory"
    const val TCP_KEEP_ALIVE_INTERVAL = "tcpKeepAliveInterval"
    const val LOG_LEVEL = "logLevel"
    const val LOG_MAX_LINE = "logMaxLine"
    const val MTU = "mtu"
    const val VPN_SESSION_NAME = "vpnSessionName"
    const val TUN_INTERFACE_NAME = "tunInterfaceName"
    const val TUN_STRICT_ROUTE = "tunStrictRoute"
    const val TUN_AUTO_REDIRECT = "tunAutoRedirect"
    const val ALLOW_APPS_BYPASS_VPN = "allowAppsBypassVpn"
    const val ALWAYS_SHOW_ADDRESS = "alwaysShowAddress"
    const val BLURRED_ADDRESS = "blurredAddress"
    const val PRIVACY_MODE = "privacyMode"

    // NTP Settings
    const val ENABLE_NTP = "ntpEnable"
    const val NTP_SERVER = "ntpAddress"
    const val NTP_PORT = "ntpPort"
    const val NTP_INTERVAL = "ntpInterval"

    // Protocol Settings
    const val UPLOAD_SPEED = "uploadSpeed"
    const val DOWNLOAD_SPEED = "downloadSpeed"
    const val PROVIDER_HYSTERIA2 = "providerHysteria2"
    const val PROVIDER_JUICITY = "providerJuicity"
    const val PROVIDER_NAIVE = "providerNaive"
    const val CUSTOM_PLUGIN_PREFIX = "customPluginPrefix"

    const val ACQUIRE_WAKE_LOCK = "acquireWakeLock"

    const val TUN_IMPLEMENTATION = "tunImplementation"
    const val PROFILE_TRAFFIC_STATISTICS = "profileTrafficStatistics"

    const val CERT_PROVIDER = "certProvider"
    const val DISABLE_PROCESS_TEXT = "disableProcessText"

    const val TRAFFIC_DESCENDING = "trafficDescending"
    const val TRAFFIC_SORT_MODE = "trafficSortMode"
    const val TRAFFIC_CONNECTION_QUERY = "trafficConnectionQuery"

    const val SPEED_TEST_URL = "speedTestURL"
    const val SPEED_TEST_UPLOAD_URL = "speedTestUploadURL"
    const val SPEED_TEST_UPLOAD_LENGTH = "speedTestUploadLength"
    const val SPEED_TEST_TIMEOUT = "speedTestTimeout"

    const val PROFILE_ID = "profileId"
    const val PROFILE_GROUP = "profileGroup"
    const val PROFILE_CURRENT = "profileCurrent"

    const val RULES_FIRST_CREATE = "rulesFirstCreate"
    const val SIMPLE_MODE = "simpleMode"
    const val DEFAULT_SUBSCRIPTIONS_BOOTSTRAPPED = "defaultSubscriptionsBootstrapped"
    const val FIRST_LAUNCH_SUBSCRIPTION_UI_REFRESH_DONE = "firstLaunchSubscriptionUiRefreshDone"
    const val DEFAULT_PER_APP_BOOTSTRAPPED = "defaultPerAppBootstrapped"
    const val AUTO_SELECT_FALLBACK_QUEUE = "autoSelectFallbackQueue"
    const val AUTO_SELECT_FALLBACK_INDEX = "autoSelectFallbackIndex"
    const val AUTO_SELECT_LAST_KNOWN_GOOD = "autoSelectLastKnownGood"
    const val AUTO_SELECT_LAST_FULL_PROBE_AT = "autoSelectLastFullProbeAt"
    const val AUTO_SELECT_PROXY_ID_SET_HASH = "autoSelectProxyIdSetHash"
    const val AUTO_SELECT_LAST_PROBE_WHITELIST_ONLY = "autoSelectLastProbeWhitelistOnly"
    const val AUTO_SELECT_LAST_KNOWN_GOOD_URL_AT = "autoSelectLastKnownGoodUrlAt"
    const val AUTO_SELECT_LAST_KNOWN_GOOD_URL_PROFILE_ID = "autoSelectLastKnownGoodUrlProfileId"
    const val AUTO_SELECT_LAST_HANDOFF_PRESERVE_OK_AT = "autoSelectLastHandoffPreserveOkAt"
    const val AUTO_SELECT_LAST_DEGRADED_PROFILE_ID = "autoSelectLastDegradedProfileId"
    const val AUTO_SELECT_LAST_DEGRADED_AT = "autoSelectLastDegradedAt"
    const val PROBE_2K_PERSISTENCE_ENABLED = "probe2kPersistenceEnabled"
    const val PROBE_2K_WARM_RANKING_ENABLED = "probe2kWarmRankingEnabled"
    const val PROBE_2K_BUILTIN_FALLBACK_CAP_ENABLED = "probe2kBuiltinFallbackCapEnabled"
    const val PROBE_2K_BACKGROUND_SCHEDULER_ENABLED = "probe2kBackgroundSchedulerEnabled"
    const val PROBE_2K_LAST_BACKGROUND_RUN_AT = "probe2kLastBackgroundRunAt"
    const val PROBE_2K_POWER_PRESET = "probe2kPowerPreset"
    const val PROBE_2K_BACKGROUND_BATCH_SIZE = "probe2kBackgroundBatchSize"
    const val PROBE_2K_BACKGROUND_TCP_WORKERS = "probe2kBackgroundTcpWorkers"
    const val PROBE_2K_LAST_SELECTION_REASON = "probe2kLastSelectionReason"
    const val PROBE_2K_SCAN_CHECKED = "probe2kScanChecked"
    const val PROBE_2K_SCAN_TOTAL = "probe2kScanTotal"
    const val PROBE_2K_POOL_ALIVE = "probe2kPoolAlive"
    const val PROBE_2K_POOL_CANDIDATE = "probe2kPoolCandidate"
    const val PROBE_2K_POOL_SUSPECT = "probe2kPoolSuspect"
    const val PROBE_2K_POOL_DEAD = "probe2kPoolDead"
    const val PROBE_2K_POOL_CEMETERY = "probe2kPoolCemetery"
    const val PROBE_2K_POOL_UNKNOWN = "probe2kPoolUnknown"
    const val PROBE_2K_WARM_RESERVE_COUNT = "probe2kWarmReserveCount"
    const val PROBE_2K_WARM_RESERVE_STATUS = "probe2kWarmReserveStatus"
    const val USER_POOL_MODE = "userPoolMode"
    const val SWITCH_USE_FULL_PROFILE_PICKER = "switchUseFullProfilePicker"
    const val EXPERT_CONNECT_RECOVER_ENABLED = "expertConnectRecoverEnabled"
    const val SIMPLE_MODE_LAST_BACKGROUND_SUB_REFRESH_AT = "simpleModeLastBackgroundSubRefreshAt"
    const val SIMPLE_MODE_WL_SKIP_TUNNEL_HEALTH_CHECK = "simpleModeWlSkipTunnelHealthCheck"
    const val SIMPLE_MODE_TELEGRAM_PROBE = "simpleModeTelegramProbe"
    const val AUTO_CONNECT_PAUSED_UNTIL_GOOGLE = "autoConnectPausedUntilGoogle"
    const val SIMPLE_MODE_PREPARE_VERIFIED_PROFILE_ID = "simpleModePrepareVerifiedProfileId"
    const val SIMPLE_MODE_ACTIVITY = "simpleModeActivity"
    const val SIMPLE_MODE_VPN_SESSION_EXPECTED = "simpleModeVpnSessionExpected"
    const val SIMPLE_MODE_VPN_LAST_HEARTBEAT_MS = "simpleModeVpnLastHeartbeatMs"
    const val SIMPLE_MODE_PENDING_CARRIER_RECONNECT_AT = "simpleModePendingCarrierReconnectAt"
    const val NETWORK_UPLINK_IDENTITY = "networkUplinkIdentity"
    const val WL_SWEEP_CACHE_FINGERPRINT = "wlSweepCacheFingerprint"
    const val WL_SWEEP_CACHE_AT_MS = "wlSweepCacheAtMs"
    const val WL_SWEEP_CACHE_URL_VERIFIED_IDS = "wlSweepCacheUrlVerifiedIds"
    const val WL_SWEEP_CACHE_TCP_ALIVE_IDS = "wlSweepCacheTcpAliveIds"
    const val SUBSCRIPTION_UPDATE_PARALLELISM_FOREGROUND = "subscriptionUpdateParallelismForeground"
    const val SUBSCRIPTION_UPDATE_PARALLELISM_BACKGROUND = "subscriptionUpdateParallelismBackground"
    const val SUBSCRIPTION_CONNECT_REFRESH_BUDGET_MS = "subscriptionConnectRefreshBudgetMs"
    const val SUBSCRIPTION_FALLBACK_REFRESH_BUDGET_MS = "subscriptionFallbackRefreshBudgetMs"

    const val APP_UPDATE_CHECK_ENABLED = "appUpdateCheckEnabled"
    const val APP_UPDATE_CHECK_INTERVAL_HOURS = "appUpdateCheckIntervalHours"
    const val APP_UPDATE_CHECK_NOW = "appUpdateCheckNow"
    const val APP_UPDATE_LAST_CHECK_AT = "appUpdateLastCheckAt"
    const val APP_UPDATE_DISMISSED_VERSION_CODE = "appUpdateDismissedVersionCode"
    const val APP_UPDATE_REOPEN_DOWNLOADED = "appUpdateReopenDownloaded"
    const val APP_UPDATE_LAST_DOWNLOADED_PATH = "appUpdateLastDownloadedPath"

    const val SUBSCRIPTION_CATALOG_ENABLED = "subscriptionCatalogEnabled"
    const val SUBSCRIPTION_CATALOG_URL = "subscriptionCatalogUrl"
    const val SUBSCRIPTION_CATALOG_CHECK_INTERVAL_HOURS = "subscriptionCatalogCheckIntervalHours"
    const val SUBSCRIPTION_CATALOG_LAST_CHECK_AT = "subscriptionCatalogLastCheckAt"
    const val SUBSCRIPTION_CATALOG_LAST_APPLIED_GENERATION = "subscriptionCatalogLastAppliedGeneration"
    const val SUBSCRIPTION_CATALOG_LAST_APPLIED_HASH = "subscriptionCatalogLastAppliedHash"
    const val SUBSCRIPTION_CATALOG_CHECK_NOW = "subscriptionCatalogCheckNow"

    const val SUBSCRIPTION_UA_VERSION_HAPP = "subscriptionUaVersionHapp"
    const val SUBSCRIPTION_UA_VERSION_V2RAYNG = "subscriptionUaVersionV2rayNg"
    const val SUBSCRIPTION_UA_VERSION_V2RAYTUN = "subscriptionUaVersionV2rayTun"
    const val SUBSCRIPTION_UA_VERSION_INCY = "subscriptionUaVersionIncy"

    const val DAHUSIM_QUICK_ACCESS_ENABLED = "dahusimQuickAccessEnabled"

}

object AlertType {
    const val COMMON = 0

    // message: none
    const val MISSING_PLUGIN = 1

    // message: plugin name
    const val NEED_WIFI_PERMISSION = 2

    /** Simple mode: all auto-selected servers failed; message unused */
    const val SIMPLE_MODE_ALL_SERVERS_DEAD = 3
}

fun logLevelString(level: Int): String = when (level) {
    0 -> "panic"
    1 -> "fatal"
    2 -> "error"
    3 -> "warn"
    4 -> "info"
    5 -> "debug"
    6 -> "trace"
    else -> "info"
}

object TunImplementation {
    const val GVISOR = 0
    const val SYSTEM = 1
    const val MIXED = 2
}

object GroupType {
    const val BASIC = 0
    const val SUBSCRIPTION = 1
}

object SubscriptionType {
    const val RAW = 0
    const val OOCv1 = 1
    const val SIP008 = 2
}

object GroupOrder {
    const val ORIGIN = 0
    const val BY_NAME = 1
    const val BY_DELAY = 2
}

object MuxType {
    const val H2MUX = 0
    const val SMUX = 1
    const val YAMUX = 2
}

object MuxStrategy {
    const val MAX_CONNECTIONS = 0
    const val MIN_STREAMS = 1
    const val MAX_STREAMS = 2
}

object Action {
    const val SERVICE = "fr.husi.SERVICE"
    const val CLOSE = "fr.husi.CLOSE"
    const val RELOAD = "fr.husi.RELOAD"

    // const val SWITCH_WAKE_LOCK = "fr.husi.SWITCH_WAKELOCK"
    const val RESET_UPSTREAM_CONNECTIONS = "fr.husi.RESET_UPSTREAM_CONNECTIONS"
    const val SWITCH_SERVER = "fr.husi.SWITCH_SERVER"
}

object TrafficSortMode {
    const val START = 0
    const val INBOUND = 1
    const val SRC = 2
    const val DST = 3
    const val UPLOAD = 4
    const val DOWNLOAD = 5
    const val MATCHED_RULE = 6

    val values
        get() = listOf(
            START,
            INBOUND,
            SRC,
            DST,
            UPLOAD,
            DOWNLOAD,
            MATCHED_RULE,
        )
}

object RuleProvider {
    const val OFFICIAL = 0
    const val LOYALSOLDIER = 1
    const val CHOCOLATE4U = 2
    const val CUSTOM = 3

    fun hasUnstableBranch(provider: Int): Boolean {
        return provider in OFFICIAL..LOYALSOLDIER
    }
}

object RouteQuickProfile {
    const val MANUAL = 0
    const val RU_DIRECT_ONLY = 1
    const val RU_DIRECT_WITH_BLOCKED_AND_AI_PROXY = 2
}

object NetworkInterfaceStrategy {
    const val DEFAULT = 0
    const val HYBRID = 1
    const val FALLBACK = 2
}

object CertProvider {
    const val SYSTEM = 0
    const val MOZILLA = 1
    const val SYSTEM_AND_USER = 2 // Put it last because Go may fix the bug one day.
    const val CHROME = 3
}

object ProtocolProvider {
    const val CORE = 0
    const val PLUGIN = 1
}

// https://github.com/chen08209/FlClash/blob/6c27f2e2f1ac033e62f09b7b30b2710dd0d13bb4/lib/models/config.dart#L110-L128
const val DEFAULT_HTTP_BYPASS = """# If you are annoyed with default value, just set a "#"
# Chinese apps that can't work with http proxy
*zhihu.com
*zhimg.com
*jd.com
100ime-iat-api.xfyun.cn
*360buyimg.com
# local
localhost
*.local
127.*
10.*
172.16.*
172.17.*
172.18.*
172.19.*
172.2*
172.30.*
172.31.*
192.168.*"""
