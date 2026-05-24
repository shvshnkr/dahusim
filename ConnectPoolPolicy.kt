@@
-        return if (whitelistBuiltinOnly) {
+        val wlEnabled = whitelistBuiltinOnly && !DataStore.simpleModeDisableWhitelistBuiltinPool
+        return if (wlEnabled) {
             buildWhitelist(
                 allProxies = allProxies,
                 builtinProxies = builtinProxies,
                 builtinIds = builtinIds,
                 handoffIds = handoffIds,
                 subscriptionWlIds = tag.subscriptionWlProxyIds,
                 subsWlMarkedCount = tag.subsWlMarkedCount,
                 wlGroupCount = tag.wlGroupIds.size,
                 probeStates = probeStates,
             )
         } else {
             buildOpen(
                 allProxies = allProxies,