package fr.husi.fmt

import fr.husi.database.DataStore
import fr.husi.utils.simpleModeLog
import java.io.File

private fun hasRootBinary(): Boolean {
    return File("/system/bin/su").exists() || File("/system/xbin/su").exists()
}

internal actual fun SingBoxOptions.Inbound_TunOptions.applyPlatformConfig() {
    // sing-box Android (VpnService): strict_route is documented as "Not implemented".
    // DataStore.tunStrictRoute still affects stack selection in ConfigBuilder (gvisor path).
    // https://sing-box.sagernet.org/clients/android/features/
    if (DataStore.tunAutoRedirect) {
        if (hasRootBinary()) {
            // sing-box requires auto_route whenever auto_redirect is enabled.
            auto_route = true
            auto_redirect = true
        } else {
            // Non-root Android devices cannot use auto_redirect; enabling it causes
            // immediate startup failure and fallback loops in simple mode.
            simpleModeLog(
                "SimpleMode",
                "H6 auto_redirect_skipped_no_root",
            )
        }
    }
}