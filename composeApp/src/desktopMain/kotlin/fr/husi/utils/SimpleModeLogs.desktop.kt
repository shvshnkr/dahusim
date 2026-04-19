package fr.husi.utils

internal actual fun simpleModeLog(tag: String, message: String) = Unit

internal actual fun simpleModeDebugEvent(
    runId: String,
    hypothesisId: String,
    location: String,
    message: String,
    data: Map<String, String>,
) = Unit

internal actual fun canShareSimpleModeLogs(): Boolean = false

internal actual suspend fun shareSimpleModeLogs() = Unit
