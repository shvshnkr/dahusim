package fr.husi.simplemode

enum class SessionRecoverContext {
    PostConnectBootstrap,
    PostConnectExhausted,
    SessionHealth,
    StallWatchdog,
}

enum class SessionRecoverOutcome {
    SoftKeepConnected,
    HardRecovered,
    NotRecovered,
}
