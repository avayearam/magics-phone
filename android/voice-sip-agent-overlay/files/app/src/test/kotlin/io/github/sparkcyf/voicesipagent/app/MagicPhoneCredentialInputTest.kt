package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagicPhoneCredentialInputTest {
    private val token = "mpa_" + "ab".repeat(24)

    @Test fun acceptsIssuedTokenAndUppercaseHex() {
        assertEquals(token, MagicPhoneCredentialInput.deviceToken(token))
        assertEquals(token, MagicPhoneCredentialInput.deviceToken(token.uppercase()))
    }
    @Test fun supportsPastingFromAdminScreen() {
        assertEquals(token, MagicPhoneCredentialInput.deviceToken("توکن: " + token + "\u200F"))
        assertEquals(token, MagicPhoneCredentialInput.deviceToken("\"" + token + "\""))
    }
    @Test fun rejectsMalformedLegacyAndMultipleTokens() {
        assertNull(MagicPhoneCredentialInput.deviceToken("magic-phone-test-token"))
        assertNull(MagicPhoneCredentialInput.deviceToken("mpa_" + "ab".repeat(23)))
        assertNull(MagicPhoneCredentialInput.deviceToken(token + "f"))
        assertNull(MagicPhoneCredentialInput.deviceToken(token + " " + token))
    }
    @Test fun explainsMixedUpCredentials() {
        assertTrue(MagicPhoneCredentialInput.deviceTokenMessage("AQ." + "x".repeat(35)).contains("Google"))
        assertTrue(MagicPhoneCredentialInput.googleApiKeyMessage(token).contains("توکن"))
    }
    @Test fun acceptsGoogleKeyFormatsButNotDeviceTokens() {
        assertEquals("AQ." + "x".repeat(35), MagicPhoneCredentialInput.googleApiKey(" AQ." + "x".repeat(35) + " "))
        assertEquals("AIza" + "x".repeat(35), MagicPhoneCredentialInput.googleApiKey("AIza" + "x".repeat(35)))
        assertNull(MagicPhoneCredentialInput.googleApiKey(token))
        assertNull(MagicPhoneCredentialInput.googleApiKey("AQ." + "x".repeat(35) + "."))
    }
}
