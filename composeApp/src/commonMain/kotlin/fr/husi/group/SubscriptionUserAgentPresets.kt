package fr.husi.group

import fr.husi.database.DataStore
import fr.husi.database.SubscriptionBean
import fr.husi.ktx.USER_AGENT
import fr.husi.ktx.blankAsNull
import fr.husi.ktx.generateUserAgent

object SubscriptionFetchProfile {
    const val DEFAULT = 0
    const val HAPP = 1
    const val CUSTOM = 2
    const val V2RAYNG = 4
    const val V2RAYTUN = 5
    const val INCY = 6

    val selectablePresets = listOf(DEFAULT, HAPP, V2RAYNG, V2RAYTUN, INCY, CUSTOM)

    fun hasClientVersionTemplate(profile: Int): Boolean =
        profile == HAPP || profile == V2RAYNG || profile == V2RAYTUN || profile == INCY

    fun resolveUserAgent(subscription: SubscriptionBean): String =
        SubscriptionUserAgentPresets.resolveUserAgent(subscription)
}

object SubscriptionUserAgentPresets {

  // Factory defaults — verify against real clients before release (mitm / Play builds).
    object FactoryVersions {
        const val HAPP = "3.21.1"
        const val V2RAYNG = "2.1.8"
        const val V2RAYTUN = "5.21.68"
        const val INCY = "2.1.1"
    }

    fun defaultVersions(): Map<Int, String> = mapOf(
        SubscriptionFetchProfile.HAPP to FactoryVersions.HAPP,
        SubscriptionFetchProfile.V2RAYNG to FactoryVersions.V2RAYNG,
        SubscriptionFetchProfile.V2RAYTUN to FactoryVersions.V2RAYTUN,
        SubscriptionFetchProfile.INCY to FactoryVersions.INCY,
    )

    fun inferFetchProfileForNewLink(link: String): Int = when (SubscriptionSourceKind.inferFromLink(link)) {
        SubscriptionSourceKind.GITHUB -> SubscriptionFetchProfile.DEFAULT
        else -> SubscriptionFetchProfile.HAPP
    }

    fun templateVersion(profile: Int): String = when (profile) {
        SubscriptionFetchProfile.HAPP ->
            DataStore.subscriptionUaVersionHapp.blankAsNull() ?: FactoryVersions.HAPP
        SubscriptionFetchProfile.V2RAYNG ->
            DataStore.subscriptionUaVersionV2rayNg.blankAsNull() ?: FactoryVersions.V2RAYNG
        SubscriptionFetchProfile.V2RAYTUN ->
            DataStore.subscriptionUaVersionV2rayTun.blankAsNull() ?: FactoryVersions.V2RAYTUN
        SubscriptionFetchProfile.INCY ->
            DataStore.subscriptionUaVersionIncy.blankAsNull() ?: FactoryVersions.INCY
        else -> ""
    }

    fun versionForSubscription(subscription: SubscriptionBean): String {
        val override = subscription.userAgentVersionOverride.blankAsNull()
        if (override != null) return override
        return templateVersion(subscription.fetchProfile)
    }

    fun formatPresetUserAgent(profile: Int, version: String): String = when (profile) {
        SubscriptionFetchProfile.HAPP -> "happ/$version"
        SubscriptionFetchProfile.V2RAYNG -> "v2rayNG/$version"
        SubscriptionFetchProfile.V2RAYTUN -> "v2RayTun/$version"
        SubscriptionFetchProfile.INCY -> "incy/$version"
        else -> ""
    }

    fun previewUserAgent(
        fetchProfile: Int,
        customUserAgent: String = "",
        userAgentVersionOverride: String = "",
    ): String {
        val subscription = SubscriptionBean().apply {
            this.fetchProfile = fetchProfile
            this.customUserAgent = customUserAgent
            this.userAgentVersionOverride = userAgentVersionOverride
        }
        return resolveUserAgent(subscription)
    }

    fun resolveUserAgent(subscription: SubscriptionBean): String = when (subscription.fetchProfile) {
        SubscriptionFetchProfile.DEFAULT -> USER_AGENT
        SubscriptionFetchProfile.CUSTOM -> generateUserAgent(subscription.customUserAgent)
        SubscriptionFetchProfile.HAPP,
        SubscriptionFetchProfile.V2RAYNG,
        SubscriptionFetchProfile.V2RAYTUN,
        SubscriptionFetchProfile.INCY,
        -> formatPresetUserAgent(
            subscription.fetchProfile,
            versionForSubscription(subscription),
        )
        else -> generateUserAgent(subscription.customUserAgent)
    }

    fun resetTemplateToDefault(profile: Int) {
        when (profile) {
            SubscriptionFetchProfile.HAPP ->
                DataStore.subscriptionUaVersionHapp = FactoryVersions.HAPP
            SubscriptionFetchProfile.V2RAYNG ->
                DataStore.subscriptionUaVersionV2rayNg = FactoryVersions.V2RAYNG
            SubscriptionFetchProfile.V2RAYTUN ->
                DataStore.subscriptionUaVersionV2rayTun = FactoryVersions.V2RAYTUN
            SubscriptionFetchProfile.INCY ->
                DataStore.subscriptionUaVersionIncy = FactoryVersions.INCY
        }
    }

    fun isLikelyUserAgentRejection(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("invalid client") ||
            lower.contains("403") ||
            lower.contains("forbidden") ||
            lower.contains("user-agent") ||
            lower.contains("user agent")
    }

    fun isLikelyPanelHtmlResponse(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return true
        val lower = trimmed.lowercase()
        if (lower.contains("<html") || lower.contains("<!doctype html")) return true
        if (lower.contains("login") && lower.contains("<form")) return true
        return !looksLikeProxyUriBody(trimmed)
    }

    private fun looksLikeProxyUriBody(body: String): Boolean {
        val schemes = listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "tuic://")
        return schemes.any { body.contains(it, ignoreCase = true) }
    }

    fun uaRetryLadder(link: String): List<Int> {
        val kind = SubscriptionSourceKind.inferFromLink(link)
        val inferred = inferFetchProfileForNewLink(link)
        val presets = mutableListOf<Int>()
        if (inferred != SubscriptionFetchProfile.DEFAULT) presets += inferred
        for (profile in listOf(
            SubscriptionFetchProfile.HAPP,
            SubscriptionFetchProfile.INCY,
            SubscriptionFetchProfile.V2RAYTUN,
            SubscriptionFetchProfile.V2RAYNG,
        )) {
            if (profile !in presets) presets += profile
        }
        if (kind == SubscriptionSourceKind.GITHUB && SubscriptionFetchProfile.DEFAULT !in presets) {
            presets += SubscriptionFetchProfile.DEFAULT
        }
        return presets
    }

    fun shouldOfferRetry(subscription: SubscriptionBean, failureMessage: String): Boolean {
        if (isLikelyUserAgentRejection(failureMessage)) return true
        if (isLikelyPanelHtmlResponse(failureMessage)) return true
        if (subscription.fetchProfile != SubscriptionFetchProfile.DEFAULT) return false
        return SubscriptionSourceKind.inferFromLink(subscription.link) == SubscriptionSourceKind.WEB
    }

    fun suggestRetryPreset(subscription: SubscriptionBean): Int {
        return SubscriptionFetchProfile.HAPP
    }
}
