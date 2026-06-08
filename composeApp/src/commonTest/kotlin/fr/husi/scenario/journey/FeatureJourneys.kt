package fr.husi.scenario.journey

data class FeatureJourney(
    val id: String,
    val userPromise: String,
    val entryPoints: List<String>,
    val testClass: String,
)

object FeatureJourneys {
    val all: List<FeatureJourney> = listOf(
        FeatureJourney(
            id = "sub_add_import",
            userPromise = "Import URL → USER subscription group with proxies in DB",
            entryPoints = listOf("MainViewModel", "ImportLinkInteractor"),
            testClass = "fr.husi.scenario.journey.SubscriptionAddByImportJourneyTest",
        ),
        FeatureJourney(
            id = "sub_add_settings",
            userPromise = "Manual subscription creation → same USER ownership contract",
            entryPoints = listOf("GroupSettingsViewModel", "LibraryAddSheet"),
            testClass = "fr.husi.scenario.journey.SubscriptionAddBySettingsJourneyTest",
        ),
        FeatureJourney(
            id = "sub_survives_bootstrap",
            userPromise = "After restart/bootstrap USER ownership and link unchanged",
            entryPoints = listOf("DefaultUserBootstrap"),
            testClass = "fr.husi.scenario.journey.SubscriptionSurvivesBootstrapJourneyTest",
        ),
        FeatureJourney(
            id = "profile_import_standalone",
            userPromise = "vless/share → user BASIC group, not builtin",
            entryPoints = listOf("ImportLinkInteractor.importStandaloneProfiles"),
            testClass = "fr.husi.scenario.journey.StandaloneProfileImportJourneyTest",
        ),
        FeatureJourney(
            id = "connect_user_pool_priority",
            userPromise = "USER proxies rank above managed in PRIORITY pool mode",
            entryPoints = listOf("UserPoolMode.PRIORITY", "AutoServerSelector"),
            testClass = "fr.husi.scenario.journey.UserPoolConnectJourneyTest",
        ),
        FeatureJourney(
            id = "library_manual_flat_list",
            userPromise = "Library Manual tab shows user-owned BASIC profiles, not builtin relay",
            entryPoints = listOf("ManualServersViewModel", "ManualServersPolicy"),
            testClass = "fr.husi.scenario.journey.LibraryManualFlatListJourneyTest",
        ),
    )

    fun byId(id: String): FeatureJourney? = all.find { it.id == id }
}
