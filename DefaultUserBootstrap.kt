@@
-        WhitelistBuiltinBootstrap.ensureGroupAndProfiles()
+        if (!DataStore.simpleModeDisableWhitelistBuiltinPool) {
+            WhitelistBuiltinBootstrap.ensureGroupAndProfiles()
+        }