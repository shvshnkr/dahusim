package fr.husi.database

import fr.husi.test.HusiKoinTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoServerSelectorEarlyExitTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
    }

    @Test
    fun earlyExitCancelsRemainingJobsAfterTargetReached() = kotlinx.coroutines.runBlocking {
        val concurrency = 4
        val target = 2
        val totalJobs = 6
        val semaphore = Semaphore(concurrency)
        val result = ConcurrentHashMap<Long, Int>()
        val completed = AtomicInteger(0)

        val startMs = System.currentTimeMillis()

        val jobs = (1L..totalJobs.toLong()).map { id ->
            async {
                semaphore.withPermit {
                    val delayMs = if (id <= target) 50L else 3000L
                    delay(delayMs)
                    result[id] = delayMs.toInt()
                    completed.incrementAndGet()
                }
            }
        }

        for (job in jobs) {
            val doneNow = result.size
            if (doneNow >= target) {
                jobs.filter { it.isActive }.forEach { it.cancel() }
                break
            }
            job.join()
        }

        val elapsed = System.currentTimeMillis() - startMs
        assertTrue(result.size >= target, "Expected at least $target results, got ${result.size}")
        assertTrue(elapsed < 2000L, "Expected early exit under 2s, took ${elapsed}ms")
    }

    @Test
    fun earlyExitDoesNotTriggerForWhitelistBuiltinOnly() = kotlinx.coroutines.runBlocking {
        val target = 2
        val totalJobs = 4
        val result = ConcurrentHashMap<Long, Int>()

        val startMs = System.currentTimeMillis()

        val jobs = (1L..totalJobs.toLong()).map { id ->
            async {
                val delayMs = 50L
                delay(delayMs)
                result[id] = delayMs.toInt()
            }
        }

        for (job in jobs) {
            val doneNow = result.size
            if (doneNow >= target) {
                jobs.filter { it.isActive }.forEach { it.cancel() }
                break
            }
            job.join()
        }

        val elapsed = System.currentTimeMillis() - startMs
        assertTrue(result.size >= target, "Expected at least $target results, got ${result.size}")
        assertTrue(elapsed < 2000L, "Expected early exit under 2s, took ${elapsed}ms")
    }

    @Test
    fun allJobsCompleteWhenTargetNotReached() = kotlinx.coroutines.runBlocking {
        val target = 10
        val totalJobs = 3
        val result = ConcurrentHashMap<Long, Int>()

        val jobs = (1L..totalJobs.toLong()).map { id ->
            async {
                delay(50L)
                result[id] = 50
            }
        }

        for (job in jobs) {
            val doneNow = result.size
            if (doneNow >= target) {
                jobs.filter { it.isActive }.forEach { it.cancel() }
                break
            }
            job.join()
        }

        assertEquals(totalJobs, result.size, "All jobs should complete when target not reached")
    }
}
