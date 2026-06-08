package fr.husi.subscription

import fr.husi.database.GroupManager
import fr.husi.database.ProxyGroup
import fr.husi.group.GroupUpdater
import fr.husi.ui.ImportTargetResolver.applyUserImportOwnership

object UserSubscriptionAddCoordinator {

    suspend fun add(
        parsed: ProxyGroup,
        byUser: Boolean,
        updateImmediately: Boolean = true,
    ): ProxyGroup {
        val group = GroupManager.createGroup(parsed.applyUserImportOwnership())
        if (updateImmediately) {
            GroupUpdater.executeUpdate(group, byUser)
        }
        return group
    }
}
