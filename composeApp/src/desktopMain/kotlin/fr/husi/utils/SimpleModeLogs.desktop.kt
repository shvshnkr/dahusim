package fr.husi.utils

import fr.husi.ktx.openFilePath
import fr.husi.repository.resolveRepository

internal actual fun simpleModeLog(tag: String, message: String) {
    SimpleModeLogStore.log(tag, message)
}

internal actual fun simpleModeDebugEvent(
    runId: String,
    hypothesisId: String,
    location: String,
    message: String,
    data: Map<String, String>,
) = Unit

internal actual fun canShareSimpleModeLogs(): Boolean = true

internal actual suspend fun shareSimpleModeLogs() {
    val shareFile = SimpleModeLogStore.buildExportCopy()
    val appendix = buildString {
        appendLine()
        appendLine("=== Logcat / platform ===")
        appendLine()
        append(SendLog.buildLog(resolveRepository().externalAssetsDir))
    }
    shareFile.appendText(appendix, Charsets.UTF_8)
    openFilePath(shareFile.absolutePath)
}

internal actual suspend fun clearSimpleModeLogs() {
    SimpleModeLogStore.clearAppLog()
}
