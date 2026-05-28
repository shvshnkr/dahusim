package fr.husi.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwitchScreenPolicyTest {

    @Test
    fun resolveInitialModeWarmWhenAvailableAndNotFullPicker() {
        assertEquals(
            SwitchScreenMode.WARM_COMPARE,
            SwitchScreenPolicy.resolveInitialMode(
                useFullProfilePicker = false,
                warmAvailable = true,
            ),
        )
    }

    @Test
    fun resolveInitialModeFullWhenPickerPreference() {
        assertEquals(
            SwitchScreenMode.FULL_PICKER,
            SwitchScreenPolicy.resolveInitialMode(
                useFullProfilePicker = true,
                warmAvailable = true,
            ),
        )
    }

    @Test
    fun resolveInitialModeFullWhenWarmUnavailable() {
        assertEquals(
            SwitchScreenMode.FULL_PICKER,
            SwitchScreenPolicy.resolveInitialMode(
                useFullProfilePicker = false,
                warmAvailable = false,
            ),
        )
    }

    @Test
    fun shouldShowWarmCompareOnlyWhenWarmAndNotFullPicker() {
        assertTrue(SwitchScreenPolicy.shouldShowWarmCompare(warmAvailable = true, useFullProfilePicker = false))
        assertFalse(SwitchScreenPolicy.shouldShowWarmCompare(warmAvailable = true, useFullProfilePicker = true))
        assertFalse(SwitchScreenPolicy.shouldShowWarmCompare(warmAvailable = false, useFullProfilePicker = false))
    }

    @Test
    fun shouldShowFullPickerWhenPreferenceOrWarmUnavailable() {
        assertTrue(SwitchScreenPolicy.shouldShowFullPicker(useFullProfilePicker = true, warmAvailable = true))
        assertTrue(SwitchScreenPolicy.shouldShowFullPicker(useFullProfilePicker = false, warmAvailable = false))
        assertFalse(SwitchScreenPolicy.shouldShowFullPicker(useFullProfilePicker = false, warmAvailable = true))
    }
}
