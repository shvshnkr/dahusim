package fr.husi.simplemode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleModeVpnOwnershipPolicyTest {

    @Test
    fun explicitReleaseReasons() {
        assertTrue(SimpleModeVpnOwnershipPolicy.isExplicitOwnershipRelease("simple_disconnect"))
        assertTrue(SimpleModeVpnOwnershipPolicy.isExplicitOwnershipRelease("full_manual_connect"))
        assertTrue(SimpleModeVpnOwnershipPolicy.isExplicitOwnershipRelease("full_profile_select_connect"))
        assertFalse(SimpleModeVpnOwnershipPolicy.isExplicitOwnershipRelease("navigation_open_configuration"))
    }

    @Test
    fun browsingFullUiRetainsOwnership() {
        assertTrue(SimpleModeVpnOwnershipPolicy.retainsOwnershipWhenBrowsingFullUi())
    }
}
