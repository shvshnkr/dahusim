package fr.husi.subscription

import fr.husi.database.GroupManager
import fr.husi.database.ProxyGroup
import fr.husi.group.GroupUpdater
import fr.husi.ktx.Logs
import fr.husi.ui.ImportTargetResolver.applyUserImportOwnership

object UserSubscriptionAddCoordinator {

    suspend fun add(
        parsed: ProxyGroup,
        byUser: Boolean,
        updateImmediately: Boolean = true,
    ): ProxyGroup {
        val group = GroupManager.createGroup(parsed.applyUserImportOwnership())
        Logs.d(
            "UserSubscriptionAddCoordinator: added id=${group.id} byUser=$byUser" +
                " updateImmediately=$updateImmediately name=${group.name}",
        )
        if (updateImmediately) {
            GroupUpdater.executeUpdate(group, byUser)
        }
        return group
    }
}
