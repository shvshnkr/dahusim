package fr.husi.fmt

/**
 * Live tunnel configs must not reference remote rule-sets: routing rules ship in the APK and
 * are seeded into geo/ without network. When a rule-set tag has no local geo/<tag>.srs file the
 * config build fails with this error instead of emitting a remote rule-set URL (which sing-box
 * would fetch synchronously on start — a github dependency at connect time).
 */
class RuleSetUnavailableException(
    val missingRuleSets: List<String>,
) : Exception(
    "No local routing rule-sets: ${missingRuleSets.joinToString(", ")}",
)
