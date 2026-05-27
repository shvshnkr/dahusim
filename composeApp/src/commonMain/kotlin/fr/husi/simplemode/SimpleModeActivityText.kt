package fr.husi.simplemode

internal const val ACTIVITY_CONNECTION_UNSTABLE_RECHECKING = "Connection unstable, rechecking…"

/** Pre-connect: subscriptions, probes, ranking — not an active VPN session yet. */
internal fun isSimpleModePrepareActivity(text: String): Boolean {
    if (text.isBlank()) return false
    return text.startsWith("Refreshing") ||
        text.startsWith("Finding") ||
        text.startsWith("Checking") ||
        text.startsWith("Verifying last") ||
        text.startsWith("Testing ") ||
        text.startsWith("Ranking ") ||
        text.startsWith("Updating") ||
        text.startsWith("Allow VPN") ||
        text.startsWith("Return to app")
}

/** VPN bring-up or in-tunnel health / fallback while service is starting or up. */
internal fun isSimpleModeVpnProgressActivity(text: String): Boolean {
    if (text.isBlank()) return false
    return text.startsWith("Connecting to server") ||
        text.startsWith("Verifying internet") ||
        text.startsWith("Starting VPN") ||
        text.startsWith("Network changed") ||
        text.contains("switching", ignoreCase = true) ||
        text.contains("trying next", ignoreCase = true) ||
        text.contains("unreachable", ignoreCase = true) ||
        text.contains("degraded", ignoreCase = true) ||
        text.contains("unstable", ignoreCase = true) ||
        text.contains("rechecking", ignoreCase = true) ||
        text.contains("Connection error", ignoreCase = true)
}

/** Any non-empty activity line that should keep the connect button flow busy. */
internal fun isSimpleModeProgressActivity(text: String): Boolean =
    isSimpleModePrepareActivity(text) || isSimpleModeVpnProgressActivity(text)
