package fr.husi.scenario.network

import fr.husi.bg.RuleSetBootstrapCallbacks
import fr.husi.bg.connectWithRuleSetBootstrap
import fr.husi.bg.isRuleSetBootstrapFailure
import fr.husi.bg.shouldRetryRuleSetBootstrapLocal
import fr.husi.fmt.RuleSetUnavailableException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun ruleSetUnavailableErrorDoesNotRetryWithLocal() = runBlocking {
        // Live configs are local-only: RuleSetUnavailableException means the missing local file
        // cannot be fixed by re-running the bootstrap loop — the error must propagate as-is.
        var buildCalls = 0
        val error = assertFailsWith<RuleSetUnavailableException> {
            connectWithRuleSetBootstrap(
                callbacks = RuleSetBootstrapCallbacks(hasLocalRuleSetFiles = { true }),
            ) {
                buildCalls++
                throw RuleSetUnavailableException(listOf("geoip-ru-blocked"))
            }
        }
        assertEquals(listOf("geoip-ru-blocked"), error.missingRuleSets)
        assertEquals(1, buildCalls)
    }

    @Test
    fun ruleSetUnavailableIsNotARemoteBootstrapFailure() {
        val error = RuleSetUnavailableException(listOf("geoip-ru-blocked"))
        // BaseService classifies it separately (H36 missing-local); it must never enter the
        // remote-bootstrap retry/fallback-walk branch.
        assertFalse(isRuleSetBootstrapFailure(error))
    }

    @Test
    fun isRuleSetBootstrapFailureDetectsSingBoxMessage() {
        val error = IllegalStateException("failed to initialize rule-set geoip-cn")
        assertTrue(isRuleSetBootstrapFailure(error))
    }

    @Test
    fun isRuleSetBootstrapFailureDetectsParseMissingLocalSrsMessage() {
        val error = IllegalStateException(
            "create service: initialize router: parse rule-set[1]: open " +
                "/storage/emulated/0/Android/data/fr.husi/files/geo/geoip-ru-blocked.srs: " +
                "no such file or directory",
        )
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

    @Test
    fun rulesetStartsWithInitialPreferLocalOnWl() = runBlocking {
        val preferLocalFlags = mutableListOf<Boolean>()
        connectWithRuleSetBootstrap(
            callbacks = RuleSetBootstrapCallbacks(hasLocalRuleSetFiles = { true }),
            initialPreferLocal = true,
        ) { preferLocal ->
            preferLocalFlags += preferLocal
        }
        // WL uplink: first attempt is already local-first, github-raw is never touched.
        assertEquals(listOf(true), preferLocalFlags)
    }

    @Test
    fun isRuleSetBootstrapFailureDetects503WithRuleSetContext() {
        val error = IllegalStateException(
            "failed to load rule-set [geoip-ru-blocked]: Get " +
                "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru-blocked.srs: " +
                "unexpected HTTP response status: 503",
        )
        assertTrue(isRuleSetBootstrapFailure(error))
    }

    @Test
    fun isRuleSetBootstrapFailureIgnoresHttpStatusWithoutRuleSetContext() {
        val error = IllegalStateException(
            "connection to proxy: dial tcp 1.2.3.4:443: unexpected HTTP response status: 503",
        )
        assertFalse(isRuleSetBootstrapFailure(error))
    }

    @Test
    fun retryPolicyFirstFailWithLocalFilesRetries() {
        assertTrue(shouldRetryRuleSetBootstrapLocal(alreadyForcedPreferLocal = false, hasLocalRuleSetFiles = true))
    }

    @Test
    fun retryPolicySecondFailIsFinal() {
        assertFalse(shouldRetryRuleSetBootstrapLocal(alreadyForcedPreferLocal = true, hasLocalRuleSetFiles = true))
    }

    @Test
    fun retryPolicyNoLocalFilesIsFinal() {
        assertFalse(shouldRetryRuleSetBootstrapLocal(alreadyForcedPreferLocal = false, hasLocalRuleSetFiles = false))
    }
}
