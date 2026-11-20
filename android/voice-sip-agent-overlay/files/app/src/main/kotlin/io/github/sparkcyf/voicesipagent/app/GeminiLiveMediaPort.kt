package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import java.util.ArrayDeque
import java.util.Base64

/**
 * Adapts cellular PCM (8 or 16 kHz, mono PCM16LE) to the Magic Phone direct-origin bridge
 * protocol (16 kHz input, 24 kHz output). MediaBridge calls this on the
 * cellular clock, so writeFrame must remain non-blocking.
 *
 * Gemini audio arrives over a network WebSocket, not the cellular clock. A small adaptive
 * jitter buffer decouples those clocks and prevents a single delayed packet becoming a
 * 20 ms silence gap on the caller's line.
 */
internal class GeminiLiveMediaPort(
    override val format: PcmFormat,
    private val diagnostic: (String) -> Unit = {},
    private val sendBase64Audio: (String) -> Boolean,
) : MediaPort, AutoCloseable {
    override val isClockSource: Boolean = false
    private val frames = ArrayDeque<ShortArray>()
    private val maxFrames = 50
    private val minPrebufferFrames = 9   // 180 ms, matching the verified browser player.
    private val maxPrebufferFrames = 22  // 440 ms, close to the browser player's 450 ms ceiling.
    @Volatile private var targetPrebufferFrames = minPrebufferFrames
    private var playbackStarted = false
    private var turnActive = false
    private var turnUnderruns = 0
    private val frame = ShortArray(format.frameSamples)
    private var frameFill = 0
    private val triple = ShortArray(3)
    private var tripleFill = 0
    private var downsampleSum = 0L
    private var downsampleCount = 0
    private var lastOutputChunkAtNanos = 0L
    @Volatile private var closed = false
    @Volatile private var inFrames = 0L
    @Volatile private var inVoiced = 0L
    @Volatile private var inPeak = 0
    @Volatile private var wsSendFailures = 0L
    @Volatile private var outChunks = 0L
    @Volatile private var outPeak = 0
    @Volatile private var outUnderruns = 0L
    @Volatile private var outPrebufferWaits = 0L
    @Volatile private var outDroppedFrames = 0L
    @Volatile private var outMaxQueue = 0
    @Volatile private var outMaxGapMs = 0L

    /** Never log actual audio bytes. Only amplitude/count/timing statistics every five seconds. */
    private fun peakOf(samples: ShortArray): Int {
        var max = 0
        for (sample in samples) max = maxOf(max, kotlin.math.abs(sample.toInt()))
        return max
    }

    private fun peakOfPcm24(bytes: ByteArray): Int {
        var max = 0
        for (i in bytes.indices step 2) {
            val value = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
            max = maxOf(max, kotlin.math.abs(value))
        }
        return max
    }

    override fun setClockListener(listener: io.github.sparkcyf.voicesipagent.voice.FrameClockListener?) = Unit

    @Synchronized
    override fun readFrame(): ShortArray {
        if (closed) return format.silence()

        if (!playbackStarted) {
            if (frames.isEmpty()) return format.silence()
            if (turnActive && frames.size < targetPrebufferFrames) {
                outPrebufferWaits++
                return format.silence()
            }
            playbackStarted = true
        }

        if (frames.isEmpty()) {
            // Empty queue during a live Gemini turn means network jitter/starvation.
            // Between completed turns, silence is expected and must not inflate the buffer.
            if (turnActive) {
                outUnderruns++
                turnUnderruns++
                targetPrebufferFrames = minOf(maxPrebufferFrames, targetPrebufferFrames + 3)
            }
            playbackStarted = false
            return format.silence()
        }
        return frames.removeFirst()
    }

    /** Called by MediaBridge every 20 ms with the caller's uplink audio. */
    override fun writeFrame(frameIn: ShortArray) {
        if (closed || frameIn.size != format.frameSamples) return
        val pcm16 = if (format.sampleRate == 16000) frameIn else upsample8To16(frameIn)
        val bytes = ByteArray(640)
        for (i in pcm16.indices) {
            val sample = pcm16[i].toInt()
            bytes[i * 2] = sample.toByte()
            bytes[i * 2 + 1] = (sample shr 8).toByte()
        }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val sent = runCatching { sendBase64Audio(encoded) }.getOrDefault(false)
        if (!sent) wsSendFailures++
        val peak = peakOf(frameIn)
        if (peak > 300) inVoiced++
        inPeak = maxOf(inPeak, peak)
        inFrames++
        if (inFrames % 250L == 0L) {
            val report = "audio path: inFrames=" + inFrames + " inPeak=" + inPeak +
                " inVoiced=" + inVoiced + " wsSendFailures=" + wsSendFailures +
                " outChunks=" + outChunks + " outPeak=" + outPeak +
                " outQueue=" + queuedFrames() + " outPrebufferMs=" + (targetPrebufferFrames * 20) +
                " outUnderruns=" + outUnderruns + " outPrebufferWaits=" + outPrebufferWaits +
                " outDroppedFrames=" + outDroppedFrames + " outMaxQueue=" + outMaxQueue +
                " outMaxGapMs=" + outMaxGapMs
            runCatching { diagnostic(report) }
            inPeak = 0
            inVoiced = 0
            outPeak = 0
            outMaxQueue = queuedFrames()
            outMaxGapMs = 0
        }
    }

    /** Gemini Live audio is PCM16LE mono at 24 kHz. Chunks may split at any sample boundary. */
    @Synchronized
    fun onGeminiPcm24k(bytes: ByteArray) {
        require(bytes.size % 2 == 0 && bytes.size <= 262144) { "invalid Gemini PCM16 chunk" }
        if (closed) return
        if (!turnActive) turnUnderruns = 0
        turnActive = true
        val now = System.nanoTime()
        if (lastOutputChunkAtNanos != 0L) {
            val gapMs = (now - lastOutputChunkAtNanos) / 1_000_000L
            outMaxGapMs = maxOf(outMaxGapMs, gapMs)
        }
        lastOutputChunkAtNanos = now
        outChunks++
        outPeak = maxOf(outPeak, peakOfPcm24(bytes))
        for (i in bytes.indices step 2) {
            val sample = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort()
            if (format.sampleRate == 16000) {
                triple[tripleFill++] = sample
                if (tripleFill == 3) {
                    append(triple[0])
                    append(((triple[1].toInt() + triple[2].toInt()) / 2).toShort())
                    tripleFill = 0
                }
            } else {
                downsampleSum += sample.toInt()
                downsampleCount++
                if (downsampleCount == 3) {
                    append((downsampleSum / 3).toShort())
                    downsampleSum = 0
                    downsampleCount = 0
                }
            }
        }
    }

    /** Called after Gemini marks the current assistant audio turn complete. */
    @Synchronized
    fun endTurn() {
        if (closed) return
        turnActive = false
        // A short final response must drain even if it never filled the normal prebuffer.
        if (frames.isEmpty()) playbackStarted = false
        else if (!playbackStarted) playbackStarted = true
        if (turnUnderruns == 0 && targetPrebufferFrames > minPrebufferFrames) {
            targetPrebufferFrames--
        }
        turnUnderruns = 0
        lastOutputChunkAtNanos = 0L
    }

    @Synchronized
    fun interrupt() {
        frames.clear()
        frameFill = 0
        tripleFill = 0
        downsampleSum = 0
        downsampleCount = 0
        playbackStarted = false
        turnActive = false
        turnUnderruns = 0
        lastOutputChunkAtNanos = 0L
    }

    @Synchronized
    fun queuedFrames(): Int = frames.size

    @Synchronized
    override fun close() {
        closed = true
        interrupt()
    }

    private fun append(sample: Short) {
        frame[frameFill++] = sample
        if (frameFill == frame.size) {
            if (frames.size >= maxFrames) {
                frames.removeFirst()
                outDroppedFrames++
            }
            frames.addLast(frame.copyOf())
            outMaxQueue = maxOf(outMaxQueue, frames.size)
            frameFill = 0
        }
    }

    private fun upsample8To16(input: ShortArray): ShortArray {
        val output = ShortArray(input.size * 2)
        for (i in input.indices) {
            val current = input[i].toInt()
            val next = if (i + 1 < input.size) input[i + 1].toInt() else current
            output[i * 2] = input[i]
            output[i * 2 + 1] = ((current + next) / 2).toShort()
        }
        return output
    }
}
