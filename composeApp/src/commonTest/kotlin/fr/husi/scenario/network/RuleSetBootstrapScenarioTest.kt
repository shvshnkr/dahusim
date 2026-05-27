package fr.husi.scenario.network

import fr.husi.bg.RuleSetBootstrapCallbacks
import fr.husi.bg.connectWithRuleSetBootstrap
import fr.husi.bg.isRuleSetBootstrapFailure
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleSetBootstrapScenarioTest {

    @Test
    fun rulesetRemoteFailLocalOkRetriesWithPreferLocal() = runBlocking {
        var preferLocalPassed = false
        var buildCalls = 0
        connectWithRuleSetBootstrap(
            callbacks = RuleSetBootstrapCallbacks(hasLocalRuleSetFiles = { true }),
        ) { preferLocal ->
            buildCalls++
            if (!preferLocal) {
                throw IllegalStateException("initialize rule-set: remote blocked")
            }
            preferLocalPassed = preferLocal
        }
        assertEquals(2, buildCalls)
        assertTrue(preferLocalPassed)
    }

    @Test
    fun rulesetRemoteFailNoLocalPropagatesError() = runBlocking {
        assertFailsWith<IllegalStateException> {
            connectWithRuleSetBootstrap(
                callbacks = RuleSetBootstrapCallbacks(hasLocalRuleSetFiles = { false }),
            ) {
                throw IllegalStateException("initialize rule-set: no local geo")
            }
        }
    }

    @Test
    fun rulesetWlRemoteBlockedUsesLocalOnSecondPass() = runBlocking {
        val preferLocalFlags = mutableListOf<Boolean>()
        connectWithRuleSetBootstrap(
            callbacks = RuleSetBootstrapCallbacks(hasLocalRuleSetFiles = { true }),
        ) { preferLocal ->
            preferLocalFlags += preferLocal
            if (!preferLocal) {
                throw IllegalStateException("initialize rule-set: forbidden on WL uplink")
            }
        }
        assertEquals(listOf(false, true), preferLocalFlags)
    }

    @Test
    fun nonRulesetErrorDoesNotRetry() = runBlocking {
        assertFailsWith<IllegalStateException> {
            connectWithRuleSetBootstrap(
                callbacks = RuleSetBootstrapCallbacks(hasLocalRuleSetFiles = { true }),
            ) {
                throw IllegalStateException("mixed port bind failed")
            }
        }
    }

    @Test
    fun isRuleSetBootstrapFailureDetectsSingBoxMessage() {
        val error = IllegalStateException("failed to initialize rule-set geoip-cn")
        assertTrue(isRuleSetBootstrapFailure(error))
    }

    @Test
    fun fakeBuildLoadSequenceMatchesProductionShape() = runBlocking {
        var loaded = false
        connectWithRuleSetBootstrap(
            callbacks = RuleSetBootstrapCallbacks(hasLocalRuleSetFiles = { true }),
        ) { preferLocal ->
            if (!preferLocal) {
                throw IllegalStateException("initialize rule-set")
            }
            loaded = true
        }
        assertTrue(loaded)
    }
}
