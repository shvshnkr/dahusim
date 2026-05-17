package fr.husi.simplemode

/** True while [fr.husi.database.DataStore.simpleModeActivity] shows live connect/adapt progress. */
internal fun isSimpleModeProgressActivity(text: String): Boolean {
    if (text.isBlank()) return false
    return text.startsWith("Testing ") ||
        text.startsWith("Ranking ") ||
        text.startsWith("Restricted") ||
        text.startsWith("Finding") ||
        text.startsWith("Refreshing") ||
        text.startsWith("Checking") ||
        text.startsWith("Verifying") ||
        text.startsWith("Updating") ||
        text.startsWith("Network changed") ||
        text.contains("network", ignoreCase = true) ||
        text.contains("server", ignoreCase = true)
}
