package fr.husi.group

import fr.husi.database.ProxyGroup
import fr.husi.database.SubscriptionBean
import fr.husi.ktx.Logs
import fr.husi.ktx.SubscriptionFoundException
import fr.husi.ui.ImportLinkInteractor
import fr.husi.ui.ImportTargetResolver.applyUserImportOwnership

sealed interface SubscriptionImportPlan {
    data class Ready(
        val group: ProxyGroup,
        val fetchProfile: Int,
        val proxyCount: Int,
    ) : SubscriptionImportPlan

    data class NeedsUaPicker(
        val group: ProxyGroup,
        val triedProfiles: List<Int>,
    ) : SubscriptionImportPlan

    data class Failed(val message: String) : SubscriptionImportPlan
}

object SubscriptionImportPreview {

    private val interactor = ImportLinkInteractor()

    suspend fun prepare(link: String): SubscriptionImportPlan {
        val parsed = interactor.parseSubscription(link)
            ?: return SubscriptionImportPlan.Failed("invalid subscription link").also {
                Logs.d("SubscriptionImportPreview: invalid subscription link")
            }
        return prepare(parsed)
    }

    suspend fun prepare(group: ProxyGroup): SubscriptionImportPlan {
        val owned = group.applyUserImportOwnership()
        val sub = owned.subscription ?: return SubscriptionImportPlan.Failed("missing subscription").also {
            Logs.d("SubscriptionImportPreview: missing subscription")
        }
        if (sub.link.isBlank()) return SubscriptionImportPlan.Failed("empty subscription link").also {
            Logs.d("SubscriptionImportPreview: empty subscription link")
        }

        val ladder = SubscriptionUserAgentPresets.uaRetryLadder(sub.link)
        val tried = mutableListOf<Int>()
        for (profile in ladder) {
            tried += profile
            val preview = fetchPreview(sub.link, profile) ?: continue
            sub.fetchProfile = profile
            return SubscriptionImportPlan.Ready(
                group = owned,
                fetchProfile = profile,
                proxyCount = preview.size,
            ).also {
                Logs.d(
                    "SubscriptionImportPreview: ready fetchProfile=$profile proxies=${preview.size}",
                )
            }
        }
        return SubscriptionImportPlan.NeedsUaPicker(
            group = owned,
            triedProfiles = tried,
        ).also {
            Logs.d("SubscriptionImportPreview: needs UA picker tried=$tried")
        }
    }

    private suspend fun fetchPreview(link: String, fetchProfile: Int): List<fr.husi.fmt.AbstractBean>? {
        val subscription = SubscriptionBean().apply {
            this.link = link
            this.fetchProfile = fetchProfile
        }
        val body = try {
            SubscriptionHttpFetch.fetchText(
                SubscriptionHttpFetch.Request(
                    canonicalLink = link,
                    userAgent = SubscriptionFetchProfile.resolveUserAgent(subscription),
                    purpose = SubscriptionHttpFetch.FetchPurpose.ImportPreview,
                ),
            ).body
        } catch (_: Exception) {
            return null
        }
        if (SubscriptionUserAgentPresets.isLikelyPanelHtmlResponse(body)) return null
        return try {
            val proxies = RawUpdater.parseRaw(body)
            proxies?.takeIf { it.isNotEmpty() }
        } catch (e: SubscriptionFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
