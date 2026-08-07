package fr.husi.database

import fr.husi.bg.classifySubscriptionUpdateError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionUpdateStateStoreTest {

    @Test
    fun `permanent error jails on first failure`() {
        val now = 1_000_000L
        val next = SubscriptionUpdateStateStore.applyFailure(
            prev = null,
            groupId = 42L,
            errorClass = SubscriptionUpdateErrorClass.HTTP_PERMANENT,
            nowMs = now,
        )
        assertEquals(SubUpdateState.JAIL, next.state)
        assertEquals(1, next.failCountConsecutive)
        assertTrue(next.nextAttemptAtMs > now)
    }

    @Test
    fun `transient error stays suspect never jails`() {
        val now = 1_000_000L
        var prev: SubscriptionUpdateState? = null
        repeat(5) { i ->
            val current = SubscriptionUpdateStateStore.applyFailure(
                prev = prev,
                groupId = 7L,
                errorClass = SubscriptionUpdateErrorClass.HTTP_TRANSIENT,
                nowMs = now + i * 60_000L,
            )
            prev = current
            assertEquals(SubUpdateState.SUSPECT, current.state)
        }
        assertEquals(5, prev!!.failCountConsecutive)
    }

    @Test
    fun `transient_net does not unjail a jailed feed`() {
        val now = 1_000_000L
        val jailed = SubscriptionUpdateState(
            groupId = 99L,
            state = SubUpdateState.JAIL,
            failCountConsecutive = 3,
            lastAttemptAtMs = now - 60_000L,
            nextAttemptAtMs = now + 120_000L,
            lastErrorClass = SubscriptionUpdateErrorClass.OTHER,
        )
        val next = SubscriptionUpdateStateStore.applyFailure(
            prev = jailed,
            groupId = 99L,
            errorClass = SubscriptionUpdateErrorClass.TRANSIENT_NET,
            nowMs = now,
        )
        assertEquals(SubUpdateState.JAIL, next.state)
    }

    @Test
    fun `transient_net stays suspect never jails`() {
        val now = 1_000_000L
        val s1 = SubscriptionUpdateStateStore.applyFailure(
            prev = null,
            groupId = 8L,
            errorClass = SubscriptionUpdateErrorClass.TRANSIENT_NET,
            nowMs = now,
        )
        assertEquals(SubUpdateState.SUSPECT, s1.state)
        val s5 = SubscriptionUpdateStateStore.applyFailure(
            prev = s1.copy(failCountConsecutive = 4),
            groupId = 8L,
            errorClass = SubscriptionUpdateErrorClass.TRANSIENT_NET,
            nowMs = now + 4 * 60_000L,
        )
        assertEquals(SubUpdateState.SUSPECT, s5.state)
        assertEquals(5, s5.failCountConsecutive)
    }

    @Test
    fun `transport stays suspect never jails`() {
        val now = 1_000_000L
        var prev: SubscriptionUpdateState? = null
        repeat(5) { i ->
            val current = SubscriptionUpdateStateStore.applyFailure(
                prev = prev,
                groupId = 10L,
                errorClass = SubscriptionUpdateErrorClass.TRANSPORT,
                nowMs = now + i * 60_000L,
            )
            prev = current
            assertEquals(SubUpdateState.SUSPECT, current.state)
        }
        assertEquals(5, prev!!.failCountConsecutive)
    }

    @Test
    fun `other error jails after three strikes`() {
        val now = 1_000_000L
        var prev: SubscriptionUpdateState? = null
        val classes = listOf(
            SubscriptionUpdateErrorClass.OTHER,
            SubscriptionUpdateErrorClass.OTHER,
            SubscriptionUpdateErrorClass.OTHER,
        )
        for ((i, cls) in classes.withIndex()) {
            prev = SubscriptionUpdateStateStore.applyFailure(
                prev = prev,
                groupId = 9L,
                errorClass = cls,
                nowMs = now + i * 60_000L,
            )
        }
        assertEquals(SubUpdateState.JAIL, prev!!.state)
    }

    @Test
    fun `classify maps 404 to permanent`() {
        assertEquals(
            SubscriptionUpdateErrorClass.HTTP_PERMANENT,
            classifySubscriptionUpdateError("HTTP 404 Not Found"),
        )
    }

    @Test
    fun `classify maps eof to transport`() {
        assertEquals(
            SubscriptionUpdateErrorClass.TRANSPORT,
            classifySubscriptionUpdateError("unexpected eof"),
        )
    }

    @Test
    fun `classify maps timeout dns tls to transient_net`() {
        assertEquals(
            SubscriptionUpdateErrorClass.TRANSIENT_NET,
            classifySubscriptionUpdateError("connection timeout"),
        )
        assertEquals(
            SubscriptionUpdateErrorClass.TRANSIENT_NET,
            classifySubscriptionUpdateError("dns failure: unresolved host"),
        )
        assertEquals(
            SubscriptionUpdateErrorClass.TRANSIENT_NET,
            classifySubscriptionUpdateError("TLS handshake failure"),
        )
    }

    @Test
    fun `classify maps 503 and 429 to http_transient`() {
        assertEquals(
            SubscriptionUpdateErrorClass.HTTP_TRANSIENT,
            classifySubscriptionUpdateError("HTTP 503 Service Unavailable"),
        )
        assertEquals(
            SubscriptionUpdateErrorClass.HTTP_TRANSIENT,
            classifySubscriptionUpdateError("429 Too Many Requests"),
        )
    }
}
