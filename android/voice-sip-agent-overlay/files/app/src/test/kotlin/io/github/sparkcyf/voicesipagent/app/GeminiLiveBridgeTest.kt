package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeminiLiveBridgeTest {
    @Test fun handshakeMatchesTheExistingWorkerContract() {
        val token = "magic-phone-test-token-123456"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(token.toByteArray(Charsets.UTF_8))
        assertEquals("wss://phone.magics.site/ws?mode=phone", MagicLiveHandshake.URL)
        assertEquals("https://ai.magics.site", MagicLiveHandshake.ORIGIN)
        assertEquals(listOf("magic-phone.v1", "magic-auth.$encoded"), MagicLiveHandshake.protocols(token))
    }

    @Test(expected = IllegalArgumentException::class)
    fun handshakeRejectsControlCharacters() {
        MagicLiveHandshake.protocols("a".repeat(24) + "\n")
    }

    @Test fun widebandAudioMatchesWorkerPcmContract() {
        val sent = mutableListOf<String>()
        val port = GeminiLiveMediaPort(PcmFormat.WIDEBAND) { sent += it; true }
        port.writeFrame(ShortArray(320) { (it - 160).toShort() })
        assertEquals(640, Base64.getDecoder().decode(sent.single()).size)
        val pcm24 = ByteArray(960) { if (it % 2 == 0) 0x39 else 0x30 }
        port.onGeminiPcm24k(pcm24.copyOfRange(0, 202))
        port.onGeminiPcm24k(pcm24.copyOfRange(202, pcm24.size))
        assertEquals(1, port.queuedFrames())
        assertTrue(port.readFrame().all { it == 12345.toShort() })
        port.close()
        assertTrue(port.readFrame().all { it == 0.toShort() })
    }

    @Test fun narrowbandAudioResamplesAndBoundsThePlaybackQueue() {
        val sent = mutableListOf<String>()
        val port = GeminiLiveMediaPort(PcmFormat.NARROWBAND) { sent += it; true }
        port.writeFrame(ShortArray(160) { 1000 })
        assertEquals(640, Base64.getDecoder().decode(sent.single()).size)
        val pcm24 = ByteArray(960) { if (it % 2 == 0) 0x39 else 0x30 }
        port.onGeminiPcm24k(pcm24)
        assertEquals(1, port.queuedFrames())
        assertTrue(port.readFrame().all { it == 12345.toShort() })
        repeat(60) { port.onGeminiPcm24k(pcm24) }
        assertEquals(50, port.queuedFrames())
        port.interrupt()
        assertEquals(0, port.queuedFrames())
    }
}
