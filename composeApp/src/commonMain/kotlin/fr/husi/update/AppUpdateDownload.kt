package fr.husi.update

import java.io.File

internal object AppUpdateDownload {

    suspend fun download(url: String, destination: File) {
        AppUpdateHttp.downloadToFile(url, destination)
    }
}

internal expect fun sha256Hex(file: File): String

internal fun normalizeSha256(value: String): String = value.trim().lowercase().replace(":", "")

internal fun sha256Matches(file: File, expectedHex: String): Boolean {
    val expected = normalizeSha256(expectedHex)
    if (expected.isEmpty()) return false
    return normalizeSha256(sha256Hex(file)) == expected
}
