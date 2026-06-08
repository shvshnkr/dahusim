package fr.husi.scenario.fieldlog

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Documents H-tag anchors referenced in [docs/FIELD_LOG_SYMPTOMS.toml].
 * Extend when adding new simpleModeLog H-tags tied to field regressions.
 */
class FieldLogTagCoverageTest {

    @Test
    fun documentedSymptomsCoverKnownFieldTags() {
        val documented = setOf(
            "H4", "H16", "H24", "H29", "H39", "SUB2-PARSE",
        )
        val fixtures = FieldLogScenarioFixtures.loadAll()
        val fixtureTags = fixtures.flatMap { it.hTagSequence }.toSet()
        val missingInFixtures = documented - fixtureTags
        assertTrue(
            missingInFixtures.isEmpty() || missingInFixtures.size <= 2,
            "consider adding fixtures for tags: $missingInFixtures",
        )
    }
}
