package fr.husi.bg

import fr.husi.ktx.readableMessage

fun isRuleSetBootstrapFailure(error: Throwable): Boolean {
    val message = error.readableMessage
    if (message.contains("initialize rule-set", ignoreCase = true)) return true
    if (message.contains("parse rule-set", ignoreCase = true)) return true
    if (message.contains(".srs: no such file or directory", ignoreCase = true)) return true
    // rule-set download failures surfacing from sing-box instance start (not the config parse
    // path) keep the rule-set context but not the "initialize/parse rule-set" prefix.
    return message.contains("rule-set", ignoreCase = true) &&
        message.contains("unexpected HTTP response status", ignoreCase = true)
}

/**
 * In-memory override: force the next bootstrap to start preferLocal=true. Set by BaseService
 * when a rule-set bootstrap failure escapes the attempt loop (surfaces from sing-box instance
 * start); reset on successful connect / graceful stop. Bounds the retry to one reconnect cycle.
 */
var ruleSetBootstrapForcePreferLocal: Boolean = false

/**
 * Retry policy for the BaseService-level ruleset failure branch: retry-local only once, and
 * only when local `.srs` files actually exist (otherwise the retry would re-fetch remote and
 * fail identically).
 */
fun shouldRetryRuleSetBootstrapLocal(
    alreadyForcedPreferLocal: Boolean,
    hasLocalRuleSetFiles: Boolean,
): Boolean = !alreadyForcedPreferLocal && hasLocalRuleSetFiles

data class RuleSetBootstrapCallbacks(
    val hasLocalRuleSetFiles: () -> Boolean,
    val onAttempt: (preferLocalRuleSet: Boolean) -> Unit = {},
    val onBootstrapFailure: (preferLocalRuleSet: Boolean, error: Throwable) -> Unit = { _, _ -> },
    val onRetryWithLocal: () -> Unit = {},
)

/**
 * H36: first connect attempt may fetch remote rule-sets; on bootstrap failure retry once with local `.srs`.
 * [initialPreferLocal] starts the loop local-first (WL uplinks where github-raw is L3-blocked).
 */
suspend fun connectWithRuleSetBootstrap(
    callbacks: RuleSetBootstrapCallbacks,
    onBeforeRetry: suspend () -> Unit = {},
    initialPreferLocal: Boolean = false,
    attempt: suspend (preferLocalRuleSet: Boolean) -> Unit,
) {
    var preferLocal = initialPreferLocal
    while (true) {
        try {
            callbacks.onAttempt(preferLocal)
            attempt(preferLocal)
            return
        } catch (error: Throwable) {
            if (isRuleSetBootstrapFailure(error)) {
                callbacks.onBootstrapFailure(preferLocal, error)
            }
            if (!preferLocal && isRuleSetBootstrapFailure(error) && callbacks.hasLocalRuleSetFiles()) {
                preferLocal = true
                callbacks.onRetryWithLocal()
                onBeforeRetry()
                continue
            }
            throw error
        }
    }
}
