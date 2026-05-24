@@
-suspend fun ensureGroupAndProfiles() {
-    val groups = SagerDatabase.groupDao.allGroups().first()
-    var group = groups.find { it.name == WhitelistBuiltinProxies.GROUP_NAME }
-    if (group == null) {
-        group = GroupManager.createGroup(
-            ProxyGroup(
-                name = WhitelistBuiltinProxies.GROUP_NAME,
-                type = GroupType.BASIC,
-            ),
-        )
-    }
-    cachedGroupId = group.id
-    val groupId = group.id
-
-    val existing = SagerDatabase.proxyDao.getByGroup(groupId).first()
-    val defs = WhitelistBuiltinProxies.definitions
-
-    for ((index, def) in defs.withIndex()) {
-        val wantOrder = (index + 1).toLong()
-        val entity = existing.find {
-            it.type == ProxyEntity.TYPE_TROJAN &&
-                (it.trojanBean?.name == def.profileName)
-        }
-        val freshBean = WhitelistBuiltinProxies.trojanBean(def)
-        if (entity == null) {
-            val created = ProfileManager.createProfile(groupId, freshBean)
-            if (created.userOrder != wantOrder) {
-                created.userOrder = wantOrder
-                ProfileManager.updateProfile(created)
-            }
-        } else {
-            val bean = entity.requireBean() as TrojanBean
-            if (!bean.contentEqualsBuiltin(freshBean) || entity.userOrder != wantOrder) {
-                entity.putBean(freshBean)
-                entity.userOrder = wantOrder
-                ProfileManager.updateProfile(entity)
-            }
-        }
-    }
-
-    syncWhitelistBuiltinVlessProfiles(groupId)
-    applyBuiltinUserOrders(groupId)
-}
-
-suspend fun whitelistPoolProxies(): List<ProxyEntity> {
-    val gid = cachedGroupId ?: run {
-        ensureGroupAndProfiles()
-        cachedGroupId
-    } ?: return emptyList()
-    val list = SagerDatabase.proxyDao.getByGroup(gid).first()
-    val allowedNames = WhitelistBuiltinProxies.definitions
-        .filter { it.useInWhitelistOnlyPool }
-        .map { it.profileName }
-        .toSet()
-    return list
-        .asSequence()
-        .filter { entity ->
-            when (entity.type) {
-                ProxyEntity.TYPE_TROJAN -> {
-                    val n = (entity.trojanBean?.name).orEmpty()
-                    n in allowedNames
-                }
-                ProxyEntity.TYPE_VLESS -> {
-                    (entity.vlessBean?.name).orEmpty().startsWith(WL_VLESS_NAME_PREFIX)
-                }
-                else -> false
-            }
-        }
-        .sortedBy { it.userOrder }
-        .toList()
-}
+private fun bootstrapDisabled() = DataStore.simpleModeDisableWhitelistBuiltinPool
+
+suspend fun ensureGroupAndProfiles() {
+    if (bootstrapDisabled()) return
+    // существующая реализация
+}
+
+suspend fun whitelistPoolProxies(): List<ProxyEntity> {
+    if (bootstrapDisabled()) return emptyList()
+    // существующая реализация
+}