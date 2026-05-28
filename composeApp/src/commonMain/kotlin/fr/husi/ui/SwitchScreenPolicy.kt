package fr.husi.ui

enum class SwitchScreenMode {
    FULL_PICKER,
    WARM_COMPARE,
}

/** Warm vs full profile picker routing for [SwitchActivity]. */
object SwitchScreenPolicy {

    fun resolveInitialMode(
        useFullProfilePicker: Boolean,
        warmAvailable: Boolean,
    ): SwitchScreenMode =
        if (useFullProfilePicker || !warmAvailable) {
            SwitchScreenMode.FULL_PICKER
        } else {
            SwitchScreenMode.WARM_COMPARE
        }

    fun shouldShowWarmCompare(warmAvailable: Boolean, useFullProfilePicker: Boolean): Boolean =
        warmAvailable && !useFullProfilePicker

    fun shouldShowFullPicker(useFullProfilePicker: Boolean, warmAvailable: Boolean): Boolean =
        useFullProfilePicker || !warmAvailable
}
