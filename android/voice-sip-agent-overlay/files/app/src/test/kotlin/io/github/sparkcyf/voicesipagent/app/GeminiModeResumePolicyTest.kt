package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModeResumePolicyTest {
    @Test fun restoresOnlyExplicitOptInWithTokenAndTakeover() {
        assertTrue(GeminiModeResumePolicy.shouldRestore(true, true, true))
    }
    @Test fun doesNotRestoreWithoutUserConsent() {
        assertFalse(GeminiModeResumePolicy.shouldRestore(false, true, true))
    }
    @Test fun doesNotRestoreWithoutDeviceToken() {
        assertFalse(GeminiModeResumePolicy.shouldRestore(true, false, true))
    }
    @Test fun doesNotRestoreWithTakeoverOff() {
        assertFalse(GeminiModeResumePolicy.shouldRestore(true, true, false))
    }
}
