@@
-        WhitelistBuiltinBootstrap.ensureGroupAndProfiles()
-
-        val allProxies = SagerDatabase.proxyDao.getAll()
-        val groups = SagerDatabase.groupDao.allGroups().first()
-
-        val builtinFour = WhitelistBuiltinBootstrap.whitelistPoolProxies()
-        val builtinFourIds = builtinFour.map { it.id }.toSet()
+        if (!DataStore.simpleModeDisableWhitelistBuiltinPool) {
+            WhitelistBuiltinBootstrap.ensureGroupAndProfiles()
+        }
+        val allProxies = SagerDatabase.proxyDao.getAll()
+        val groups = SagerDatabase.groupDao.allGroups().first()
+
+        val builtinFour = if (DataStore.simpleModeDisableWhitelistBuiltinPool) emptyList() else WhitelistBuiltinBootstrap.whitelistPoolProxies()
+        val builtinFourIds = builtinFour.map { it.id }.toSet()
@@
-            whitelistBuiltinOnly = whitelistBuiltinOnly,
+            whitelistBuiltinOnly = whitelistBuiltinOnly && !DataStore.simpleModeDisableWhitelistBuiltinPool,