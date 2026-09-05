package fr.husi.simplemode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleModeAdaptExhaustPolicyTest {

    @Test
    fun belowLimitStaysSilent() {
        assertFalse(
            SimpleModeAdaptExhaustPolicy.shouldEmitNoServersAlert(2, whitelistOnly = true),
            "fewer than 3 fruitless adapt cycles must not alert",
        )
        assertFalse(
            SimpleModeAdaptExhaustPolicy.shouldEmitNoServersAlert(0, whitelistOnly = true),
            "no fruitless adapt cycles must not alert",
        )
    }

    @Test
    fun atLimitAlerts() {
        assertTrue(
            SimpleModeAdaptExhaustPolicy.shouldEmitNoServersAlert(3, whitelistOnly = true),
            "limit reached on whitelist-only network must alert",
        )
        assertTrue(
            SimpleModeAdaptExhaustPolicy.shouldEmitNoServersAlert(4, whitelistOnly = true),
            "beyond the limit must keep alerting",
        )
        assertEquals(3, SimpleModeAdaptExhaustPolicy.FRUITLESS_ADAPT_LIMIT)
    }

    @Test
    fun openNetworkNeverAlerts() {
        assertFalse(
            SimpleModeAdaptExhaustPolicy.shouldEmitNoServersAlert(3, whitelistOnly = false),
            "open network must not emit the whitelist no-servers alert",
        )
        assertFalse(
            SimpleModeAdaptExhaustPolicy.shouldEmitNoServersAlert(10, whitelistOnly = false),
            "open network must never alert regardless of count",
        )
    }
}
