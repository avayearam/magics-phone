package site.magics.phone.live

import java.util.Base64

/**
 * Codec-independent PCM transport for a future Android CallEndpoint.
 * Phone media: PCM16 little-endian, mono 16kHz, 20ms (320 samples).
 * Gemini output: PCM16 little-endian, mono 24kHz, arbitrary even-byte chunks.
 * No microphone, Android call takeover or network access is performed here.
 */
class LivePcmBridge(private val outgoing: (String) -> Boolean) {
    private val output = ShortArray(320 * 50)
    private var front = 0
    private var count = 0
    private val triple = ShortArray(3)
    private var tripleCount = 0
    private var ended = false

    @Synchronized fun onGeminiAudio24k(bytes: ByteArray) {
        require(bytes.size % 2 == 0 && bytes.size <= 262144) { "invalid Gemini PCM16 chunk" }
        if (ended) return
        for (i in bytes.indices step 2) {
            triple[tripleCount++] = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort()
            if (tripleCount == 3) {
                push(triple[0]); push(((triple[1].toInt() + triple[2].toInt()) / 2).toShort())
                tripleCount = 0
            }
        }
    }
    @Synchronized fun nextTelephoneFrame(): ShortArray {
        val frame = ShortArray(320)
        if (ended || count < frame.size) return frame
        for (i in frame.indices) {
            frame[i] = output[front]
            front = (front + 1) % output.size
            count--
        }
        return frame
    }
    fun sendTelephoneFrame16k(samples: ShortArray): Boolean {
        require(samples.size == 320) { "expected 20ms at 16kHz" }
        synchronized(this) { if (ended) return false }
        val bytes = ByteArray(640)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            bytes[2 * i] = v.toByte()
            bytes[2 * i + 1] = (v ushr 8).toByte()
        }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return outgoing("{\"type\":\"audio\",\"data\":\"" + encoded + "\"}")
    }
    @Synchronized fun interrupt() { front = 0; count = 0; tripleCount = 0 }
    @Synchronized fun end() { ended = true; interrupt() }
    @Synchronized fun queuedSamples(): Int = count
    private fun push(sample: Short) {
        if (count == output.size) { front = (front + 1) % output.size; count-- }
        output[(front + count) % output.size] = sample
        count++
    }
}
object MagicLiveHandshake {
    const val DEFAULT_WS = "wss://phone.magics.site/ws"
    const val VERSION = "magic-phone.v1"
    fun protocols(secret: String): List<String> {
        require(secret.length >= 24 && secret.none { it.code < 32 || it.code == 127 }) { "invalid access token" }
        val safe = Base64.getUrlEncoder().withoutPadding().encodeToString(secret.toByteArray(Charsets.UTF_8))
        return listOf(VERSION, "magic-auth." + safe)
    }
}
