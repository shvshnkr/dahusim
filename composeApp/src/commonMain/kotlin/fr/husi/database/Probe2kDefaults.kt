package fr.husi.database

/**
 * Tunables for 2K probe persistence. Values follow [docs/2K_PLAN.md] template until
 * telemetry overrides are wired from runtime logs.
 */
object Probe2kDefaults {
    const val TCP_PROBE_TIMEOUT_MS = 2200
    const val URL_PROBE_TIMEOUT_MS = 5000
    const val TCP_PROBE_WORKERS_OPEN = 32
    const val TCP_PROBE_WORKERS_WL = 24
    const val URL_PROBE_TOP_K_OPEN = 32
    const val URL_PROBE_TOP_K_HANDOFF = 16
    const val ALIVE_TCP_FRESH_MS = 45L * 60L * 1000L
    const val ALIVE_URL_FRESH_MS = 45L * 60L * 1000L
    const val SUSPECT_RETRY_MS = 90L * 1000L
    const val DEAD_BACKOFF_MS = 5L * 60L * 1000L
    const val CEMETERY_BACKOFF_MS = 2L * 60L * 60L * 1000L
    const val BUILTIN_FALLBACK_MAX_FRACTION = 0.28
    const val EWMA_ALPHA = 0.35
}
