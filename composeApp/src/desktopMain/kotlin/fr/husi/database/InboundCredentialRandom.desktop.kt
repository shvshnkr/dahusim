package fr.husi.database

import java.security.SecureRandom

private const val ALPHANUM = "abcdefghijklmnopqrstuvwxyz0123456789"

actual object InboundCredentialRandom {
    private val rnd = SecureRandom()

    actual fun username(): String = buildString(9) {
        append('u')
        repeat(8) { append(ALPHANUM[rnd.nextInt(ALPHANUM.length)]) }
    }

    actual fun password(): String = buildString(24) {
        repeat(24) { append(ALPHANUM[rnd.nextInt(ALPHANUM.length)]) }
    }
}
