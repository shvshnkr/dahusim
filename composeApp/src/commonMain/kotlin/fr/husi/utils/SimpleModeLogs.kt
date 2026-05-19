package fr.husi.utils

internal expect fun simpleModeLog(tag: String, message: String)

internal expect fun simpleModeDebugEvent(
    runId: String,
    hypothesisId: String,
    location: String,
    message: String,
    data: Map<String, String> = emptyMap(),
)

internal expect fun canShareSimpleModeLogs(): Boolean

internal expect suspend fun shareSimpleModeLogs()

internal expect suspend fun clearSimpleModeLogs()
