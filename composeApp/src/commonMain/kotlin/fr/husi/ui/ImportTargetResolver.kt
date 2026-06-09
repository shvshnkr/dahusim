package fr.husi.ui

import fr.husi.GroupType
import fr.husi.database.BuiltinRelayDefaults
import fr.husi.database.CatalogOwnership
import fr.husi.database.GroupManager
import fr.husi.database.GroupOrigin
import fr.husi.database.ProfileManager
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubscriptionBean
import fr.husi.database.isBuiltinRelayGroup
import fr.husi.subscription.catalog.SubscriptionCatalogDefaults
import fr.husi.database.isUserOwnedLibraryItem
import fr.husi.database.resolvedOrigin
import fr.husi.ktx.Logs
import kotlinx.coroutines.flow.first

/**
 * Central policy for where user-imported standalone profiles and subscriptions may land.
 * Built-in / catalog-managed groups are never import targets.
 */
object ImportTargetResolver {

    private fun SubscriptionBean.isBootstrapManagedSeed(): Boolean =
        managedByRemote && (
            sourceId.startsWith(SubscriptionCatalogDefaults.BUILTIN_SOURCE_PREFIX) ||
                sourceId.startsWith(SubscriptionCatalogDefaults.GITHUB_SOURCE_PREFIX)
            )

    fun ProxyGroup.isUserImportTarget(): Boolean =
        type == GroupType.BASIC && isUserOwnedLibraryItem()

    suspend fun resolveStandaloneImportGroupId(suggestedFolderName: String? = null): Long {
        val groups = SagerDatabase.groupDao.allGroups().first()
        val normalized = suggestedFolderName?.trim()?.takeIf { it.isNotBlank() }
        if (normalized != null) {
            groups.filter { it.isUserImportTarget() }
                .find { it.name.equals(normalized, ignoreCase = true) }
                ?.let {
                    Logs.d("ImportTargetResolver: standalone import folder id=${it.id} name=$normalized")
                    return it.id
                }
            val created = GroupManager.createGroup(
                ProxyGroup(name = normalized, type = GroupType.BASIC),
            )
            Logs.d("ImportTargetResolver: created standalone import folder id=${created.id} name=$normalized")
            return created.id
        }
        val ungroupedId = groups.find { it.ungrouped && it.isUserImportTarget() }?.id
            ?: ProfileManager.ensureDefaultGroupId()
        Logs.d("ImportTargetResolver: standalone import ungrouped id=$ungroupedId")
        return ungroupedId
    }

    fun ProxyGroup.applyUserImportOwnership(): ProxyGroup {
        origin = GroupOrigin.USER
        originSourceId = ""
        subscription?.let { sub ->
            sub.catalogOwnership = CatalogOwnership.USER
            sub.managedByRemote = false
            sub.sourceId = ""
        }
        return this
    }

    suspend fun createUserSubscriptionGroup(parsed: ProxyGroup): ProxyGroup =
        GroupManager.createGroup(parsed.applyUserImportOwnership()).also {
            Logs.d("ImportTargetResolver: created user subscription id=${it.id} name=${it.name}")
        }

    /**
     * One-time repair: standalone profiles wrongly stored in built-in relay, and user subscriptions
     * promoted to GH_MANAGED by legacy bootstrap seed-link matching.
     */
    suspend fun migrateMisplacedUserImports() {
        val groups = SagerDatabase.groupDao.allGroups().first()
        val ungroupedId = groups.find { it.ungrouped && it.isUserImportTarget() }?.id
            ?: ProfileManager.ensureDefaultGroupId()

        groups.filter { it.isBuiltinRelayGroup() }.forEach { builtin ->
            val profiles = SagerDatabase.proxyDao.getByGroup(builtin.id).first()
            for (profile in profiles) {
                if (profile.originSourceId.isNotBlank()) continue
                profile.groupId = ungroupedId
                ProfileManager.updateProfile(profile)
                Logs.d("ImportTargetResolver: moved profile id=${profile.id} from builtin to ungrouped")
            }
        }

        for (group in groups) {
            if (group.type != GroupType.SUBSCRIPTION) continue
            val sub = group.subscription ?: continue
            if (sub.catalogOwnership != CatalogOwnership.USER) continue
            if (sub.isBootstrapManagedSeed()) continue
            if (group.resolvedOrigin() != GroupOrigin.GH_MANAGED) continue
            group.origin = GroupOrigin.USER
            group.originSourceId = ""
            sub.managedByRemote = false
            sub.sourceId = ""
            GroupManager.updateGroup(group)
            Logs.d("ImportTargetResolver: rolled back user subscription id=${group.id} from GH_MANAGED")
        }
    }
}
