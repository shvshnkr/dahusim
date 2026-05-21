package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals

class BuiltinPoolPolicyTest {

    @Test
    fun `open net moves builtins to end of compact probe pool`() {
        val builtin = 1L
        val proxies = listOf(
            proxy(1L, order = 1),
            proxy(2L, order = 2),
            proxy(3L, order = 3),
        )
        val ordered = BuiltinPoolPolicy.reorderForCompactProbe(
            proxies = proxies,
            builtinProfileIds = setOf(builtin),
            whitelistBuiltinOnly = false,
        )
        assertEquals(listOf(2L, 3L, 1L), ordered.map { it.id })
    }

    @Test
    fun `whitelist only keeps original order`() {
        val proxies = listOf(proxy(1L, 1), proxy(2L, 2))
        val ordered = BuiltinPoolPolicy.reorderForCompactProbe(
            proxies = proxies,
            builtinProfileIds = setOf(1L),
            whitelistBuiltinOnly = true,
        )
        assertEquals(proxies, ordered)
    }

    private fun proxy(id: Long, order: Long) = ProxyEntity().apply {
        this.id = id
        userOrder = order
        groupId = 1L
    }
}
