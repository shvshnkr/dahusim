package fr.husi.update

import fr.husi.ktx.USER_AGENT
import fr.husi.libcore.Libcore
import java.io.File

internal object AppUpdateDownload {

    suspend fun download(url: String, destination: File) {
        destination.parentFile?.mkdirs()
        val client = Libcore.newHttpClient().apply { keepAlive() }
        client.newRequest().apply {
            setURL(url)
            setUserAgent(USER_AGENT)
        }.execute().writeTo(destination.absolutePath, null)
    }
}

internal expect fun sha256Hex(file: File): String

internal fun normalizeSha256(value: String): String = value.trim().lowercase().replace(":", "")

internal fun sha256Matches(file: File, expectedHex: String): Boolean {
    val expected = normalizeSha256(expectedHex)
    if (expected.isEmpty()) return false
    return normalizeSha256(sha256Hex(file)) == expected
}
