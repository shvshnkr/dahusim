package fr.husi.database

/** Cryptographically strong tokens for inbound auth (no `java.util` in commonMain). */
expect object InboundCredentialRandom {
    fun username(): String

    fun password(): String
}
