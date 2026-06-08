package fr.husi.ui

import fr.husi.GroupType
import fr.husi.database.BuiltinRelayDefaults
import fr.husi.database.CatalogOwnership
import fr.husi.database.GroupManager
import fr.husi.database.GroupOrigin
import fr.husi.database.ProfileManager
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.isBuiltinRelayGroup
import fr.husi.database.isUserOwnedLibraryItem
import fr.husi.database.resolvedOrigin
import fr.husi.ktx.Logs
import kotlinx.coroutines.flow.first

/**
 * Central policy for where user-imported standalone profiles and subscriptions may land.
 * Built-in / catalog-managed groups are never import targets.
 */
object ImportTargetResolver {

    fun ProxyGroup.isUserImportTarget(): Boolean =
        type == GroupType.BASIC && isUserOwnedLibraryItem()

    suspend fun resolveStandaloneImportGroupId(suggestedFolderName: String? = null): Long {
        val groups = SagerDatabase.groupDao.allGroups().first()
        val normalized = suggestedFolderName?.trim()?.takeIf { it.isNotBlank() }
        if (normalized != null) {
            groups.filter { it.isUserImportTarget() }
                .find { it.name.equals(normalized, ignoreCase = true) }
                ?.let { return it.id }
            return GroupManager.createGroup(
                ProxyGroup(name = normalized, type = GroupType.BASIC),
            ).id
        }
        return groups.find { it.ungrouped && it.isUserImportTarget() }?.id
            ?: ProfileManager.ensureDefaultGroupId()
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
        GroupManager.createGroup(parsed.applyUserImportOwnership())

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
