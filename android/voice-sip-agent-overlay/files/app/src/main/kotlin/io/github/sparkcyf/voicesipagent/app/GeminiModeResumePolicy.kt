package io.github.sparkcyf.voicesipagent.app

/** Restore only when the user opted in and both credentials and takeover remain enabled. */
internal object GeminiModeResumePolicy {
    fun shouldRestore(requested: Boolean, tokenConfigured: Boolean, takeover: Boolean): Boolean =
        requested && tokenConfigured && takeover
}
