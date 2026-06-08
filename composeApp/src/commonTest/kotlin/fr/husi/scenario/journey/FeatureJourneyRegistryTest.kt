package fr.husi.scenario.journey

import kotlin.test.Test
import kotlin.test.assertTrue

class FeatureJourneyRegistryTest {

    @Test
    fun registryEntriesHaveRunnableTests() {
        for (journey in FeatureJourneys.all) {
            val clazz = Class.forName(journey.testClass)
            val hasTest = clazz.declaredMethods.any { method ->
                method.annotations.any { it.annotationClass.simpleName == "Test" }
            }
            assertTrue(hasTest, "${journey.testClass} has no @Test methods")
        }
    }
}
