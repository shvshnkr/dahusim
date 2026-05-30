package fr.husi.bg

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectReachabilityRetryTest {

    @Test
    fun returnsFirstResultWhenInternetAvailable() = runTest {
        var calls = 0
        val result = ConnectReachabilityRetry.resolveWithRetry(
            hasInternet = { it > 0 },
            probe = {
                calls++
                1
            },
        )
        assertEquals(1, result)
        assertEquals(1, calls)
    }

    @Test
    fun retriesUntilInternetWithinBudget() = runTest {
        var calls = 0
        val result = ConnectReachabilityRetry.resolveWithRetry(
            maxAttempts = 3,
            retryDelayMs = 1,
            budgetMs = 500,
            hasInternet = { it > 0 },
            probe = {
                calls++
                if (calls < 3) 0 else 1
            },
        )
        assertEquals(1, result)
        assertEquals(3, calls)
    }

    @Test
    fun stopsAtMaxAttemptsWhenStillOffline() = runTest {
        var calls = 0
        val result = ConnectReachabilityRetry.resolveWithRetry(
            maxAttempts = 3,
            retryDelayMs = 1,
            budgetMs = 500,
            hasInternet = { false },
            probe = {
                calls++
                0
            },
        )
        assertEquals(0, result)
        assertEquals(3, calls)
    }

    @Test
    fun respectsBudgetWhenDelayWouldExceed() = runTest {
        var calls = 0
        ConnectReachabilityRetry.resolveWithRetry(
            maxAttempts = 5,
            retryDelayMs = 400,
            budgetMs = 100,
            hasInternet = { false },
            probe = {
                calls++
                0
            },
        )
        assertEquals(1, calls)
    }
}
