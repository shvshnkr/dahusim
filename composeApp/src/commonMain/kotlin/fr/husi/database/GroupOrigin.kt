package fr.husi.database

import fr.husi.GroupType
import fr.husi.subscription.catalog.SubscriptionCatalogDefaults

/**
 * Who owns a [ProxyGroup] for library segmentation, delete policy, and autoselect pool rules.
 *
 * [USER] — imported or manually created content.
 * [BUILTIN] — app-shipped standalone profiles (basic pool, no subscription feed).
 * [GH_MANAGED] — remote catalog / bootstrap subscription feeds.
 * [PROTECTED_BUILTIN] — reserved catalog slot ([SubscriptionCatalogDefaults.reservedBuiltinSourceId]).
 */
object GroupOrigin {
    const val USER = 0
    const val BUILTIN = 1
    const val GH_MANAGED = 2
    const val PROTECTED_BUILTIN = 3

    fun fromCatalogOwnership(ownership: Int): Int = when (ownership) {
        CatalogOwnership.GH_MANAGED -> GH_MANAGED
        CatalogOwnership.PROTECTED_RESERVED -> PROTECTED_BUILTIN
        else -> USER
    }

    fun fromSubscription(sub: SubscriptionBean): Int = when (sub.catalogOwnership) {
        CatalogOwnership.PROTECTED_RESERVED -> PROTECTED_BUILTIN
        CatalogOwnership.GH_MANAGED -> GH_MANAGED
        else -> when {
            sub.managedByRemote && (
                sub.sourceId.startsWith(SubscriptionCatalogDefaults.BUILTIN_SOURCE_PREFIX) ||
                    sub.sourceId.startsWith(SubscriptionCatalogDefaults.GITHUB_SOURCE_PREFIX)
                ) -> GH_MANAGED
            else -> USER
        }
    }
}

fun ProxyGroup.resolvedOrigin(): Int {
    subscription?.let { sub ->
        val derived = GroupOrigin.fromSubscription(sub)
        if (origin == GroupOrigin.USER && derived != GroupOrigin.USER) {
            return derived
        }
    }
    return origin
}

fun ProxyGroup.applyOriginFromSubscription(): ProxyGroup {
    subscription?.let { sub ->
        origin = GroupOrigin.fromSubscription(sub)
        if (originSourceId.isBlank() && sub.sourceId.isNotBlank()) {
            originSourceId = sub.sourceId
        }
    }
    return this
}

fun ProxyGroup.isGroupDeletable(): Boolean = resolvedOrigin() == GroupOrigin.USER

fun ProxyGroup.isUserOwnedLibraryItem(): Boolean = resolvedOrigin() == GroupOrigin.USER

fun ProxyGroup.isSystemLibraryItem(): Boolean = resolvedOrigin() != GroupOrigin.USER

fun ProxyGroup.isBuiltinRelayGroup(): Boolean =
    type == GroupType.BASIC && resolvedOrigin() == GroupOrigin.BUILTIN

/** @deprecated Use [isGroupDeletable]. */
fun ProxyGroup.isCatalogDeletable(): Boolean = isGroupDeletable()
