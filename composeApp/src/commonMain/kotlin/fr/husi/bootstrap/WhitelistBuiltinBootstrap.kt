package fr.husi.bootstrap

import fr.husi.GroupType
import fr.husi.database.GroupManager
import fr.husi.database.ProfileManager
import fr.husi.database.ProxyEntity
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.fmt.trojan.TrojanBean
import kotlinx.coroutines.flow.first

/**
 * Creates a fixed BASIC group and syncs built-in Trojan profiles for simple mode.
 */
object WhitelistBuiltinBootstrap {

    @Volatile
    private var cachedGroupId: Long? = null

    suspend fun ensureGroupAndProfiles() {
        val groups = SagerDatabase.groupDao.allGroups().first()
        var group = groups.find { it.name == WhitelistBuiltinProxies.GROUP_NAME }
        if (group == null) {
            group = GroupManager.createGroup(
                ProxyGroup(
                    name = WhitelistBuiltinProxies.GROUP_NAME,
                    type = GroupType.BASIC,
                ),
            )
        }
        cachedGroupId = group.id
        val groupId = group.id

        val existing = SagerDatabase.proxyDao.getByGroup(groupId).first()
        val defs = WhitelistBuiltinProxies.definitions

        for ((index, def) in defs.withIndex()) {
            val wantOrder = (index + 1).toLong()
            val entity = existing.find {
                it.type == ProxyEntity.TYPE_TROJAN &&
                    (it.trojanBean?.name == def.profileName)
            }
            val freshBean = WhitelistBuiltinProxies.trojanBean(def)
            if (entity == null) {
                val created = ProfileManager.createProfile(groupId, freshBean)
                if (created.userOrder != wantOrder) {
                    created.userOrder = wantOrder
                    ProfileManager.updateProfile(created)
                }
            } else {
                val bean = entity.requireBean() as TrojanBean
                if (!bean.contentEqualsBuiltin(freshBean) || entity.userOrder != wantOrder) {
                    entity.putBean(freshBean)
                    entity.userOrder = wantOrder
                    ProfileManager.updateProfile(entity)
                }
            }
        }

        applyBuiltinUserOrders(groupId)
    }

    private suspend fun applyBuiltinUserOrders(groupId: Long) {
        val all = SagerDatabase.proxyDao.getByGroup(groupId).first()
        for ((index, def) in WhitelistBuiltinProxies.definitions.withIndex()) {
            val entity = all.find {
                it.type == ProxyEntity.TYPE_TROJAN &&
                    (it.trojanBean?.name == def.profileName)
            } ?: continue
            val wantOrder = (index + 1).toLong()
            if (entity.userOrder != wantOrder) {
                entity.userOrder = wantOrder
                ProfileManager.updateProfile(entity)
            }
        }
    }

    suspend fun whitelistPoolProxies(): List<ProxyEntity> {
        val gid = cachedGroupId ?: run {
            ensureGroupAndProfiles()
            cachedGroupId
        } ?: return emptyList()
        val list = SagerDatabase.proxyDao.getByGroup(gid).first()
        val allowedNames = WhitelistBuiltinProxies.definitions
            .filter { it.useInWhitelistOnlyPool }
            .map { it.profileName }
            .toSet()
        return list
            .asSequence()
            .filter { it.type == ProxyEntity.TYPE_TROJAN }
            .filter {
                val n = (it.trojanBean?.name).orEmpty()
                n in allowedNames
            }
            .sortedBy { it.userOrder }
            .toList()
    }

    private fun TrojanBean.contentEqualsBuiltin(other: TrojanBean): Boolean {
        return serverAddress == other.serverAddress &&
            serverPort == other.serverPort &&
            password == other.password &&
            security == other.security &&
            sni == other.sni &&
            alpn == other.alpn &&
            utlsFingerprint == other.utlsFingerprint &&
            v2rayTransport == other.v2rayTransport
    }
}
