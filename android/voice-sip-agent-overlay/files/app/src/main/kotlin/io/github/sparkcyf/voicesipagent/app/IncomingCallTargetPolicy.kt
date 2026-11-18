package io.github.sparkcyf.voicesipagent.app

/**
 * Echo is a local endpoint, not a SIP extension: it must be reachable even
 * when no SIP account / cellular inbound forwarding target is configured.
 */
internal object IncomingCallTargetPolicy {
    fun resolve(loopback: Boolean, geminiMode: Boolean, sipTarget: String, sipUsername: String): String =
        when {
            loopback -> "magic-echo"
            geminiMode -> "magic-ai"
            else -> sipTarget.ifBlank { sipUsername }
        }
}
