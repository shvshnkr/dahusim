package fr.husi.scenario.fieldlog

import fr.husi.scenario.journey.FeatureJourneys
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Serializable
data class FieldLogScenarioFixture(
    val id: String,
    val buildCode: Int = 0,
    val networkHints: List<String> = emptyList(),
    val hTagSequence: List<String> = emptyList(),
    val markers: Map<String, String> = emptyMap(),
    @SerialName("classifiedFailure") val classifiedFailure: String,
    @SerialName("linkedJourney") val linkedJourney: String,
)

object FieldLogScenarioFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadAll(): List<FieldLogScenarioFixture> {
        val loader = FieldLogScenarioFixtures::class.java.classLoader
        val dir = loader.getResource("field-log-scenarios")
            ?: error("field-log-scenarios resource directory missing")
        val root = java.io.File(dir.toURI())
        return root.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?.map { file ->
                json.decodeFromString<FieldLogScenarioFixture>(file.readText())
            }
            .orEmpty()
    }
}

class FieldLogScenarioTest {

    @Test
    fun fixturesAreValidAndLinkedToRegistry() {
        val fixtures = FieldLogScenarioFixtures.loadAll()
        assertTrue(fixtures.isNotEmpty(), "expected at least one field-log fixture")
        for (fixture in fixtures) {
            assertTrue(fixture.id.isNotBlank(), "fixture id required")
            assertTrue(fixture.classifiedFailure.isNotBlank(), "${fixture.id}: classifiedFailure required")
            val journey = assertNotNull(
                FeatureJourneys.byId(fixture.linkedJourney),
                "${fixture.id}: unknown linkedJourney ${fixture.linkedJourney}",
            )
            assertTrue(journey.userPromise.isNotBlank())
        }
    }

    @Test
    fun subAddFailuresMustNotExpectZeroParseRegression() {
        val fixtures = FieldLogScenarioFixtures.loadAll()
            .filter { it.classifiedFailure.startsWith("sub_add") }
        assertTrue(fixtures.isNotEmpty())
        for (fixture in fixtures) {
            val parsed = fixture.markers["SUB2-PARSE_parsed"]?.toIntOrNull()
            if (parsed != null) {
                assertTrue(
                    parsed == 0,
                    "${fixture.id}: sub_add fixtures document parsed=0 fingerprint",
                )
            }
            assertTrue(
                fixture.linkedJourney == "sub_add_import" || fixture.linkedJourney == "sub_add_settings",
                "${fixture.id}: sub_add must link to import or settings journey",
            )
        }
    }
}
