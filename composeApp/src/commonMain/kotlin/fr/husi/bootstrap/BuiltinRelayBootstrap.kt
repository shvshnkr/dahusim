package fr.husi.bootstrap

import fr.husi.GroupType
import fr.husi.database.BuiltinRelayDefaults
import fr.husi.database.GroupManager
import fr.husi.database.GroupOrigin
import fr.husi.database.ProfileManager
import fr.husi.database.ProxyEntity
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.resolvedOrigin
import fr.husi.fmt.v2ray.VLESSBean
import fr.husi.ktx.Logs
import fr.husi.ktx.applyDefaultValues
import fr.husi.ktx.parseProxies
import kotlinx.coroutines.flow.first

object BuiltinRelayBootstrap {

    suspend fun ensureBuiltinRelayPool() {
        migrateLegacyGroups()
        val group = findOrCreateGroup()
        upsertStandaloneSeProfile(group.id)
    }

    private suspend fun migrateLegacyGroups() {
        val groups = SagerDatabase.groupDao.allGroups().first()
        val canonical = groups.find { it.originSourceId == BuiltinRelayDefaults.groupSourceId() }
        val legacy = groups.filter {
            it.name == BuiltinRelayDefaults.LEGACY_GROUP_NAME &&
                it.originSourceId != BuiltinRelayDefaults.groupSourceId()
        }
        for (old in legacy) {
            if (canonical != null && canonical.id != old.id) {
                moveProfiles(fromGroupId = old.id, toGroupId = canonical.id)
                GroupManager.deleteGroup(old.id)
                Logs.d("BuiltinRelayBootstrap: removed legacy duplicate group id=${old.id}")
            } else {
                old.name = BuiltinRelayDefaults.GROUP_NAME
                old.origin = GroupOrigin.BUILTIN
                old.originSourceId = BuiltinRelayDefaults.groupSourceId()
                GroupManager.updateGroup(old)
                Logs.d("BuiltinRelayBootstrap: migrated legacy group id=${old.id}")
            }
        }
    }

    private suspend fun moveProfiles(fromGroupId: Long, toGroupId: Long) {
        val profiles = SagerDatabase.proxyDao.getByGroup(fromGroupId).first()
        for (profile in profiles) {
            profile.groupId = toGroupId
            ProfileManager.updateProfile(profile)
        }
    }

    private suspend fun findOrCreateGroup(): ProxyGroup {
        val groups = SagerDatabase.groupDao.allGroups().first()
        groups.find { it.originSourceId == BuiltinRelayDefaults.groupSourceId() }?.let { return it }
        groups.find { it.name == BuiltinRelayDefaults.GROUP_NAME && it.type == GroupType.BASIC }?.let { existing ->
            existing.origin = GroupOrigin.BUILTIN
            existing.originSourceId = BuiltinRelayDefaults.groupSourceId()
            existing.name = BuiltinRelayDefaults.GROUP_NAME
            GroupManager.updateGroup(existing)
            return existing
        }
        return GroupManager.createGroup(
            ProxyGroup(
                name = BuiltinRelayDefaults.GROUP_NAME,
                type = GroupType.BASIC,
            ).apply {
                origin = GroupOrigin.BUILTIN
                originSourceId = BuiltinRelayDefaults.groupSourceId()
            },
            notifySubscriptionScheduler = false,
        ).also {
            Logs.d("BuiltinRelayBootstrap: created built-in relay group id=${it.id}")
        }
    }

    private suspend fun upsertStandaloneSeProfile(groupId: Long) {
        val bean = parseProxies(BuiltinRelayDefaults.STANDALONE_SE_VLESS_URI)
            .mapNotNull { it as? VLESSBean }
            .firstOrNull()
            ?: return
        bean.name = BuiltinRelayDefaults.STANDALONE_SE_PROFILE_NAME
        bean.applyDefaultValues()

        val profiles = SagerDatabase.proxyDao.getByGroup(groupId).first()
        var entity = profiles.find { it.originSourceId == BuiltinRelayDefaults.profileSourceId() }
            ?: profiles.find { it.displayName() == BuiltinRelayDefaults.LEGACY_PROFILE_NAME }
        if (entity == null) {
            entity = ProfileManager.createProfile(groupId, bean)
        } else {
            entity.putBean(bean)
        }
        entity.groupId = groupId
        entity.originSourceId = BuiltinRelayDefaults.profileSourceId()
        ProfileManager.updateProfile(entity)
    }

    fun isBuiltinManagedProfile(profile: ProxyEntity, group: ProxyGroup): Boolean =
        group.resolvedOrigin() == GroupOrigin.BUILTIN && profile.originSourceId.isNotBlank()
}
