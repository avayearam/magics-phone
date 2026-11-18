package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import java.util.Base64

fun main() {
    var checks = 0
    fun ok(value: Boolean, label: String) { checks++; check(value) { label } }

    val sent = mutableListOf<String>()
    val wide = GeminiLiveMediaPort(PcmFormat.WIDEBAND) { sent += it; true }
    val frame16 = ShortArray(320) { (it - 160).toShort() }
    wide.writeFrame(frame16)
    val bytes = Base64.getDecoder().decode(sent.single())
    ok(bytes.size == 640, "16k frame is 640 bytes")
    ok(bytes[0] == 0x60.toByte() && bytes[1] == 0xff.toByte(), "PCM16LE signed sample")

    val pcm24 = ByteArray(480 * 2) { i -> if (i % 2 == 0) 0x39 else 0x30 }
    wide.onGeminiPcm24k(pcm24.copyOfRange(0, 202))
    wide.onGeminiPcm24k(pcm24.copyOfRange(202, pcm24.size))
    ok(wide.queuedFrames() == 1, "24k chunk boundaries preserved")
    ok(wide.readFrame().all { it == 12345.toShort() }, "24k -> 16k resampling")
    wide.onGeminiPcm24k(pcm24)
    wide.interrupt()
    ok(wide.queuedFrames() == 0 && wide.readFrame().all { it == 0.toShort() }, "interrupt clears audio")
    repeat(60) { wide.onGeminiPcm24k(pcm24) }
    ok(wide.queuedFrames() == 50, "playback queue bounded")
    wide.close()
    ok(wide.readFrame().all { it == 0.toShort() }, "closed media is silent")

    val narrowSent = mutableListOf<String>()
    val narrow = GeminiLiveMediaPort(PcmFormat.NARROWBAND) { narrowSent += it; true }
    narrow.writeFrame(ShortArray(160) { 1000 })
    val up = Base64.getDecoder().decode(narrowSent.single())
    ok(up.size == 640, "8k input upsampled to 16k")
    ok((0 until 320).all { i ->
        (((up[i * 2].toInt() and 255) or (up[i * 2 + 1].toInt() shl 8)).toShort()) == 1000.toShort()
    }, "8k interpolation")
    narrow.onGeminiPcm24k(pcm24)
    ok(narrow.queuedFrames() == 1, "24k -> 8k produces one 20ms frame")
    ok(narrow.readFrame().all { it == 12345.toShort() }, "24k -> 8k output samples")
    println("PASS: $checks Gemini Live MediaPort tests")
}
