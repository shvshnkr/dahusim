package fr.husi.database

enum class UserPoolMode(val wire: Int) {
    OFF(0),
    PRIORITY(1),
    PRIORITY_FALLBACK(2),
    EXCLUSIVE(3),
    ;

    companion object {
        fun fromWire(value: Int): UserPoolMode =
            entries.find { it.wire == value } ?: OFF

        fun cycle(current: UserPoolMode): UserPoolMode = when (current) {
            OFF -> PRIORITY
            PRIORITY -> PRIORITY_FALLBACK
            PRIORITY_FALLBACK -> EXCLUSIVE
            EXCLUSIVE -> OFF
        }
    }
}

/**
 * Simple-mode policy for user-owned pool priority / exclusivity.
 */
object UserPoolPolicy {

    @Volatile
    var simpleModeUserPoolFallbackUsed: Boolean = false

    fun effectiveMode(): UserPoolMode = UserPoolMode.fromWire(DataStore.userPoolMode)

    fun shouldRunUserFirstPass(mode: UserPoolMode, userProxyIds: Set<Long>): Boolean =
        mode == UserPoolMode.PRIORITY_FALLBACK &&
            !simpleModeUserPoolFallbackUsed &&
            userProxyIds.isNotEmpty()

    fun filterProxies(
        mode: UserPoolMode,
        allProxies: List<ProxyEntity>,
        userProxyIds: Set<Long>,
    ): List<ProxyEntity> = when (mode) {
        UserPoolMode.EXCLUSIVE -> allProxies.filter { it.id in userProxyIds }
        UserPoolMode.PRIORITY_FALLBACK ->
            if (!simpleModeUserPoolFallbackUsed) {
                allProxies.filter { it.id in userProxyIds }
            } else {
                allProxies
            }
        else -> allProxies
    }

    fun filterProxyIds(
        mode: UserPoolMode,
        proxyIds: Collection<Long>,
        userProxyIds: Set<Long>,
    ): List<Long> = when (mode) {
        UserPoolMode.EXCLUSIVE -> proxyIds.filter { it in userProxyIds }
        UserPoolMode.PRIORITY_FALLBACK ->
            if (!simpleModeUserPoolFallbackUsed) {
                proxyIds.filter { it in userProxyIds }
            } else {
                proxyIds.toList()
            }
        else -> proxyIds.toList()
    }

    fun priorityBoostIds(
        mode: UserPoolMode,
        userProxyIds: Set<Long>,
        handoffIds: Set<Long>,
    ): Set<Long> = when (mode) {
        UserPoolMode.PRIORITY,
        UserPoolMode.PRIORITY_FALLBACK,
        -> userProxyIds + handoffIds
        else -> handoffIds
    }

    fun userSelectionRank(
        mode: UserPoolMode,
        profileId: Long,
        userProxyIds: Set<Long>,
    ): Int = when (mode) {
        UserPoolMode.PRIORITY,
        UserPoolMode.PRIORITY_FALLBACK,
        -> ConnectPoolPolicy.userNodeRank(profileId, userProxyIds)
        else -> 0
    }

    fun lkgAllowed(
        mode: UserPoolMode,
        profileId: Long,
        userProxyIds: Set<Long>,
    ): Boolean = when (mode) {
        UserPoolMode.EXCLUSIVE -> profileId in userProxyIds
        UserPoolMode.PRIORITY_FALLBACK ->
            !simpleModeUserPoolFallbackUsed && profileId in userProxyIds ||
                simpleModeUserPoolFallbackUsed
        else -> true
    }

    fun backgroundProbeProxyIds(
        mode: UserPoolMode,
        proxyIds: List<Long>,
        userProxyIds: Set<Long>,
    ): List<Long> = when (mode) {
        UserPoolMode.EXCLUSIVE -> proxyIds.filter { it in userProxyIds }
        UserPoolMode.PRIORITY_FALLBACK ->
            if (!simpleModeUserPoolFallbackUsed) {
                proxyIds.filter { it in userProxyIds }
            } else {
                proxyIds
            }
        else -> proxyIds
    }

    /** Hide user-owned groups in configuration UI (data kept in DB). */
    fun shouldHideGroupFromConfigurationUi(group: ProxyGroup): Boolean =
        effectiveMode() == UserPoolMode.EXCLUSIVE && UserSubscriptionTag.isUserOwnedGroup(group)
}
