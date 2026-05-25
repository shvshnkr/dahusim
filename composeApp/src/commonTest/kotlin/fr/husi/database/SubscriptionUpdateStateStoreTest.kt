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
    fun `transient error becomes suspect then jail`() {
        val now = 1_000_000L
        val suspect = SubscriptionUpdateStateStore.applyFailure(
            prev = null,
            groupId = 7L,
            errorClass = SubscriptionUpdateErrorClass.HTTP_TRANSIENT,
            nowMs = now,
        )
        assertEquals(SubUpdateState.SUSPECT, suspect.state)
        val jail = SubscriptionUpdateStateStore.applyFailure(
            prev = suspect,
            groupId = 7L,
            errorClass = SubscriptionUpdateErrorClass.HTTP_TRANSIENT,
            nowMs = now + 60_000L,
        )
        assertEquals(SubUpdateState.SUSPECT, jail.state)
        val jail3 = SubscriptionUpdateStateStore.applyFailure(
            prev = jail,
            groupId = 7L,
            errorClass = SubscriptionUpdateErrorClass.HTTP_TRANSIENT,
            nowMs = now + 120_000L,
        )
        assertEquals(SubUpdateState.JAIL, jail3.state)
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
}
