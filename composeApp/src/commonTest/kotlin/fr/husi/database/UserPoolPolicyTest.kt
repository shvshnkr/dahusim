package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserPoolPolicyTest {

    @Test
    fun exclusiveFiltersToUserProxiesOnly() {
        val userIds = setOf(1L, 2L)
        val all = listOf(proxy(1L), proxy(2L), proxy(3L))
        val filtered = UserPoolPolicy.filterProxies(UserPoolMode.EXCLUSIVE, all, userIds)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.id in userIds })
    }

    @Test
    fun priorityFallbackBeforeFallbackUsesUserOnly() {
        UserPoolPolicy.simpleModeUserPoolFallbackUsed = false
        val userIds = setOf(1L)
        val all = listOf(proxy(1L), proxy(2L))
        val filtered = UserPoolPolicy.filterProxies(UserPoolMode.PRIORITY_FALLBACK, all, userIds)
        assertEquals(1, filtered.size)
    }

    @Test
    fun priorityFallbackAfterFallbackUsesFullPool() {
        UserPoolPolicy.simpleModeUserPoolFallbackUsed = true
        val userIds = setOf(1L)
        val all = listOf(proxy(1L), proxy(2L))
        val filtered = UserPoolPolicy.filterProxies(UserPoolMode.PRIORITY_FALLBACK, all, userIds)
        assertEquals(2, filtered.size)
    }

    @Test
    fun lkgExclusiveRequiresUserProfile() {
        val userIds = setOf(5L)
        assertTrue(UserPoolPolicy.lkgAllowed(UserPoolMode.EXCLUSIVE, 5L, userIds))
        assertFalse(UserPoolPolicy.lkgAllowed(UserPoolMode.EXCLUSIVE, 9L, userIds))
    }

    @Test
    fun cycleModes() {
        assertEquals(UserPoolMode.PRIORITY, UserPoolMode.cycle(UserPoolMode.OFF))
        assertEquals(UserPoolMode.EXCLUSIVE, UserPoolMode.cycle(UserPoolMode.PRIORITY_FALLBACK))
        assertEquals(UserPoolMode.OFF, UserPoolMode.cycle(UserPoolMode.EXCLUSIVE))
    }

    private fun proxy(id: Long): ProxyEntity =
        ProxyEntity(groupId = 1L, type = 0).apply { this.id = id }
}
