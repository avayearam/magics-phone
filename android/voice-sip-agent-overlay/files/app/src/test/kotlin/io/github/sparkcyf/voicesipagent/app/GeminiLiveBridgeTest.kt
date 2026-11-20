package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeminiLiveBridgeTest {
    @Test fun handshakeMatchesDirectOriginAgentContract() {
        val token = "mpa_" + "ab".repeat(24)
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(token.toByteArray(Charsets.UTF_8))
        assertEquals("wss://voice.magics.site/ws?mode=phone", MagicLiveHandshake.URL)
        assertEquals("https://ai.magics.site", MagicLiveHandshake.ORIGIN)
        assertTrue(MagicLiveHandshake.isDeviceToken(token))
        assertEquals(listOf("magic-phone.v1", "magic-agent.$encoded"), MagicLiveHandshake.protocols(token))
    }

    @Test fun callerNumberFromTelecomIsNormalizedAndValidated() {
        assertEquals("+989121234567", MagicLiveHandshake.normalizeCallerNumber("+98 (912) 123-4567"))
        assertEquals("+989121234567", MagicLiveHandshake.normalizeCallerNumber("0098 912 123 4567"))
        assertEquals("09121234567", MagicLiveHandshake.normalizeCallerNumber("0912 123 4567"))
        assertNull(MagicLiveHandshake.normalizeCallerNumber("anonymous"))
        assertNull(MagicLiveHandshake.normalizeCallerNumber("12345"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun legacyWorkerTokenIsRejectedInsteadOfFallingBack() {
        MagicLiveHandshake.protocols("magic-phone-test-token-123456")
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedDeviceTokenIsRejected() {
        MagicLiveHandshake.protocols("mpa_" + "ab".repeat(23))
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
        assertTrue(port.readFrame().all { it == 0.toShort() }) // wait for the 180 ms jitter buffer
        repeat(8) { port.onGeminiPcm24k(pcm24) }
        assertEquals(9, port.queuedFrames())
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
        assertTrue(port.readFrame().all { it == 0.toShort() })
        repeat(8) { port.onGeminiPcm24k(pcm24) }
        assertEquals(9, port.queuedFrames())
        assertTrue(port.readFrame().all { it == 12345.toShort() })
        repeat(60) { port.onGeminiPcm24k(pcm24) }
        assertEquals(68, port.queuedFrames())
        port.interrupt()
        assertEquals(0, port.queuedFrames())
    }
    @Test fun largeGeminiAudioBurstPreservesTheBeginningOfSpeech() {
        val port = GeminiLiveMediaPort(PcmFormat.WIDEBAND) { true }

        // 300 consecutive 20 ms chunks represent six seconds of speech.
        // This exceeds the old 1-second queue and must not evict the first words.
        repeat(300) { index ->
            val sample = (1000 + index).toShort()
            val pcm24 = ByteArray(960)
            for (offset in 0 until pcm24.size step 2) {
                pcm24[offset] = sample.toByte()
                pcm24[offset + 1] = (sample.toInt() shr 8).toByte()
            }
            port.onGeminiPcm24k(pcm24)
        }

        assertEquals(300, port.queuedFrames())
        assertEquals(1000.toShort(), port.readFrame().first())
        assertEquals(1001.toShort(), port.readFrame().first())
        port.close()
    }

    @Test fun jitterBufferRebuffersAfterUnderrunAndDrainsShortFinalTurns() {
        val port = GeminiLiveMediaPort(PcmFormat.WIDEBAND) { true }
        val pcm24 = ByteArray(960) { if (it % 2 == 0) 0x39 else 0x30 }

        repeat(9) { port.onGeminiPcm24k(pcm24) }
        assertTrue(port.readFrame().all { it == 12345.toShort() })
        repeat(8) { assertTrue(port.readFrame().all { it == 12345.toShort() }) }

        // A mid-turn starvation increases the prebuffer from 180 ms to 240 ms.
        assertTrue(port.readFrame().all { it == 0.toShort() })
        repeat(9) { port.onGeminiPcm24k(pcm24) }
        assertTrue(port.readFrame().all { it == 0.toShort() })
        repeat(3) { port.onGeminiPcm24k(pcm24) }
        assertTrue(port.readFrame().all { it == 12345.toShort() })

        port.interrupt()
        assertEquals(0, port.queuedFrames())
        assertTrue(port.readFrame().all { it == 0.toShort() })

        // A short response is flushed when Gemini marks the turn complete.
        port.onGeminiPcm24k(pcm24)
        port.endTurn()
        assertTrue(port.readFrame().all { it == 12345.toShort() })
        port.close()
    }

    @Test fun silentFramesAndFailedWorkerSendsAreReportedWithoutAudioData() {
        val reports = mutableListOf<String>()
        val port = GeminiLiveMediaPort(PcmFormat.WIDEBAND, diagnostic = { reports += it }) { false }
        repeat(250) { port.writeFrame(ShortArray(320)) }
        val report = reports.single()
        assertTrue(report.contains("inFrames=250"))
        assertTrue(report.contains("inPeak=0"))
        assertTrue(report.contains("inVoiced=0"))
        assertTrue(report.contains("wsSendFailures=250"))
        assertTrue(report.contains("outChunks=0"))
        assertTrue(report.contains("outPrebufferMs=180"))
        assertTrue(report.contains("outUnderruns=0"))
        assertTrue(report.contains("outPrebufferWaits=0"))
        assertTrue(report.contains("outDroppedFrames=0"))
        assertTrue(report.contains("outMaxGapMs=0"))
        port.close()
    }

    @Test fun loudCellularSamplesProduceNonZeroInputPeak() {
        val reports = mutableListOf<String>()
        val port = GeminiLiveMediaPort(PcmFormat.NARROWBAND, diagnostic = { reports += it }) { true }
        repeat(250) { port.writeFrame(ShortArray(160) { 1200 }) }
        assertTrue(reports.single().contains("inPeak=1200"))
        assertTrue(reports.single().contains("inVoiced=250"))
        assertTrue(reports.single().contains("wsSendFailures=0"))
        port.close()
    }


}
