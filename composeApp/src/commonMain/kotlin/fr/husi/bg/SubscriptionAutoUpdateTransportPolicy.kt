package fr.husi.bg

/** When subscription auto-update should retry fetch outside the VPN tunnel. */
internal object SubscriptionAutoUpdateTransportPolicy {

    fun shouldRetryWithBypass(
        staleTransportFailure: Boolean,
        vpnConnected: Boolean,
        hasAnyInternet: Boolean,
    ): Boolean = staleTransportFailure && vpnConnected && hasAnyInternet

    fun messageLooksLikeStaleTransport(message: String): Boolean {
        val m = message.lowercase()
        if (m.isBlank()) return false
        return m.contains("eof") ||
            m.contains("deadline exceeded") ||
            m.contains("client.timeout exceeded") ||
            m.contains("broken pipe") ||
            m.contains("connection reset") ||
            m.contains("i/o timeout") ||
            m.contains("connection timed out") ||
            m.contains("tls handshake timeout") ||
            m.contains("unexpected eof")
    }
}
