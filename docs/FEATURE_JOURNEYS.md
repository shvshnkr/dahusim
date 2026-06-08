# Feature journeys (L2.5)

Machine-readable registry: [`FeatureJourneys.kt`](../composeApp/src/commonTest/kotlin/fr/husi/scenario/journey/FeatureJourneys.kt).

## Definition of done

Any **user-facing flow** change (import, subscription add, library, settings save, bootstrap ownership) must:

1. Update `FeatureJourneys.kt` when the user promise changes or a new entry point appears.
2. Extend an existing journey test or add a new class under `composeApp/src/commonTest/kotlin/fr/husi/scenario/journey/`.
3. Pass **`featureJourneyTest`** on GitHub CI ([integration-scenario-matrix.yml](../.github/workflows/integration-scenario-matrix.yml)).

Unit tests around helpers are **not** sufficient acceptance for UI/feature work.

## Local vs CI

| Command | When |
|---------|------|
| `./gradlew :composeApp:desktopTest --tests "fr.husi.scenario.journey.FeatureJourneyRegistryTest"` | Fast registry smoke (no libcore fetch in test body) |
| `./gradlew :composeApp:featureJourneyTest` | Full journeys — **CI only** (libcore, ~integration job) |
| `./gradlew :composeApp:fieldLogScenarioTest` | Field-log fixtures — CI or quick local (~2 min, no libcore HTTP) |

Do **not** ask users for manual smoke checklists; CI gates are acceptance.

## Field logs (L2.6)

Boевые `husi_simple_log_*.txt` exports → [`mine-field-logs.py`](../buildScript/ci/mine-field-logs.py) → redacted JSON in `field-log-scenarios/`.

Symptom bridge: [`FIELD_LOG_SYMPTOMS.toml`](./FIELD_LOG_SYMPTOMS.toml).

Bugfix with attached log: run miner, commit fixture + journey/fieldlog regression.

## Registered journeys

| id | user promise | test class |
|----|--------------|------------|
| `sub_add_import` | Import URL → USER group + proxies | `SubscriptionAddByImportJourneyTest` |
| `sub_add_settings` | Manual subscription → same contract | `SubscriptionAddBySettingsJourneyTest` |
| `sub_survives_bootstrap` | Bootstrap does not drift USER ownership | `SubscriptionSurvivesBootstrapJourneyTest` |
| `profile_import_standalone` | Share link → user BASIC, not builtin | `StandaloneProfileImportJourneyTest` |
| `connect_user_pool_priority` | USER proxies rank above managed | `UserPoolConnectJourneyTest` |
| `library_manual_flat_list` | Manual tab → user BASIC only, not builtin | `LibraryManualFlatListJourneyTest` |
