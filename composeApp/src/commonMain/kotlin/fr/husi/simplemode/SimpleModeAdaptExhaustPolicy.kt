package fr.husi.simplemode

/**
 * BS-S7 (field 2026-09-01 22:02-22:13): after several consecutive fruitless adaptation
 * generations on a BS uplink the user only sees a silent cycle; the honest "no working
 * servers" feedback must appear instead.
 */
internal object SimpleModeAdaptExhaustPolicy {

    const val FRUITLESS_ADAPT_LIMIT = 3

    fun shouldEmitNoServersAlert(fruitlessAdaptCount: Int, whitelistOnly: Boolean): Boolean =
        whitelistOnly && fruitlessAdaptCount >= FRUITLESS_ADAPT_LIMIT
}
