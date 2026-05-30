package fr.husi.bg

import kotlinx.coroutines.delay

internal object ConnectReachabilityRetry {

    const val DEFAULT_MAX_ATTEMPTS = 3
    const val DEFAULT_RETRY_DELAY_MS = 250L
    const val DEFAULT_BUDGET_MS = 800L

    suspend fun <T> resolveWithRetry(
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
        budgetMs: Long = DEFAULT_BUDGET_MS,
        hasInternet: (T) -> Boolean,
        probe: suspend () -> T,
    ): T {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        val deadlineMs = System.currentTimeMillis() + budgetMs
        var attempt = 0
        var last = probe()
        while (!hasInternet(last) && attempt + 1 < maxAttempts) {
            if (System.currentTimeMillis() + retryDelayMs > deadlineMs) break
            attempt++
            delay(retryDelayMs)
            last = probe()
        }
        return last
    }
}
