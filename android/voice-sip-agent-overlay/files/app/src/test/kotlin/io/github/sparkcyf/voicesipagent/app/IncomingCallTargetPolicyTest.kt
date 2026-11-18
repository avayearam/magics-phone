package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingCallTargetPolicyTest {
    @Test fun echoDoesNotRequireSipAccountOrExtension() {
        assertEquals("magic-echo", IncomingCallTargetPolicy.resolve(true, false, "", ""))
        assertEquals("magic-echo", IncomingCallTargetPolicy.resolve(true, true, "", ""))
    }

    @Test fun geminiModeKeepsItsDedicatedTarget() {
        assertEquals("magic-ai", IncomingCallTargetPolicy.resolve(false, true, "", ""))
    }

    @Test fun sipModePreservesExistingRoutingAndFallback() {
        assertEquals("4444", IncomingCallTargetPolicy.resolve(false, false, "4444", "agent"))
        assertEquals("agent", IncomingCallTargetPolicy.resolve(false, false, "", "agent"))
        assertEquals("", IncomingCallTargetPolicy.resolve(false, false, "", ""))
    }
}
