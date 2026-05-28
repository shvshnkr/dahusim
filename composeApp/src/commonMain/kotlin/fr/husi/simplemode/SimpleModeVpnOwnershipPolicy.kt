package fr.husi.simplemode

/**
 * Documents when [releaseSimpleModeVpnSession] must run vs when full UI may keep the VPN watchdog.
 */
internal object SimpleModeVpnOwnershipPolicy {

    private val explicitReleaseReasons = setOf(
        "simple_disconnect",
        "full_manual_connect",
        "full_profile_select_connect",
    )

    fun isExplicitOwnershipRelease(reason: String): Boolean = reason in explicitReleaseReasons

    /** Navigating to full UI without connect/disconnect must not release ownership (H40). */
    fun retainsOwnershipWhenBrowsingFullUi(): Boolean = true
}
