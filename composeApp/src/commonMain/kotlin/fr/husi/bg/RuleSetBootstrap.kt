package fr.husi.bg

import fr.husi.ktx.readableMessage

fun isRuleSetBootstrapFailure(error: Throwable): Boolean {
    val message = error.readableMessage
    if (message.contains("initialize rule-set", ignoreCase = true)) return true
    if (message.contains("parse rule-set", ignoreCase = true)) return true
    return message.contains(".srs: no such file or directory", ignoreCase = true)
}

data class RuleSetBootstrapCallbacks(
    val hasLocalRuleSetFiles: () -> Boolean,
    val onAttempt: (preferLocalRuleSet: Boolean) -> Unit = {},
    val onBootstrapFailure: (preferLocalRuleSet: Boolean, error: Throwable) -> Unit = { _, _ -> },
    val onRetryWithLocal: () -> Unit = {},
)

/**
 * H36: first connect attempt may fetch remote rule-sets; on bootstrap failure retry once with local `.srs`.
 */
suspend fun connectWithRuleSetBootstrap(
    callbacks: RuleSetBootstrapCallbacks,
    onBeforeRetry: suspend () -> Unit = {},
    attempt: suspend (preferLocalRuleSet: Boolean) -> Unit,
) {
    var preferLocal = false
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
