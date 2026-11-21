package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.voice.AbstractCallLeg
import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A direct Gemini Live call endpoint. It replaces the SIP side of CallBridge;
 * the cellular endpoint and its Shizuku audio path remain unchanged.
 *
 * The phone uses an agent-specific device token only. The per-device Gemini API key stays encrypted on the origin server.
 * No call audio or credentials are written to logs.
 */
internal class GeminiLiveEndpoint(
    private val tokenProvider: () -> String?,
    private val format: PcmFormat,
    private val log: (String) -> Unit = {},
) : CallEndpoint, AutoCloseable {
    override val name = "gemini-live"
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val ids = AtomicInteger(1)
    private val active = ConcurrentHashMap<String, GeminiLeg>()
    private val listeners = CopyOnWriteArrayList<EndpointStatusListener>()
    @Volatile private var closed = false
    @Volatile private var lastStatus = EndpointStatus(false, "agent device token not configured")

    override val status: EndpointStatus
        get() {
            if (closed) return EndpointStatus(false, "stopped")
            val token = runCatching { tokenProvider() }.getOrNull()
            return if (validToken(token)) EndpointStatus(true, "origin agent token configured")
            else EndpointStatus(false, "agent device token not configured")
        }

    override fun setIncomingCallHandler(handler: IncomingCallHandler?) = Unit
    override fun addStatusListener(listener: EndpointStatusListener) { listeners.addIfAbsent(listener) }
    override fun removeStatusListener(listener: EndpointStatusListener) { listeners.remove(listener) }

    override fun dial(target: String, caller: Party, line: Int?): CallLeg {
        val id = "g\${ids.getAndIncrement()}"
        val leg = GeminiLeg(id, target, caller, line)
        if (closed) {
            leg.fail(HangupCause.UNAVAILABLE, "endpoint stopped")
            return leg
        }
        val token = runCatching { tokenProvider() }.getOrNull()
        if (!validToken(token)) {
            leg.fail(HangupCause.UNAVAILABLE, "agent device token missing")
            updateStatus(EndpointStatus(false, "agent device token not configured"))
            return leg
        }
        active[id] = leg
        updateStatus(EndpointStatus(true, "connecting to Gemini Live"))
        leg.start(token!!)
        return leg
    }

    override fun close() {
        if (closed) return
        closed = true
        active.values.toList().forEach { it.hangup(HangupCause.NORMAL) }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        updateStatus(EndpointStatus(false, "stopped"))
    }

    private fun updateStatus(next: EndpointStatus) {
        if (lastStatus == next) return
        lastStatus = next
        listeners.forEach { listener -> runCatching { listener.onStatusChanged(this, next) } }
    }

    private fun remove(leg: GeminiLeg) { active.remove(leg.id, leg) }

    private fun validToken(value: String?): Boolean = MagicLiveHandshake.isDeviceToken(value)

    private inner class GeminiLeg(
        id: String,
        target: String,
        caller: Party,
        line: Int?,
    ) : AbstractCallLeg(id, "gemini-live", LegDirection.OUTBOUND, caller, target, line) {
        @Volatile private var socket: WebSocket? = null
        @Volatile private var mediaPort: GeminiLiveMediaPort? = null
        @Volatile private var ended = false
        @Volatile private var hangupScheduled = false
        @Volatile private var endCallTask: ScheduledFuture<*>? = null
        @Volatile private var setupTimeout: ScheduledFuture<*>? = null
        private val goodbyeGate = GoodbyeHangupGate()
        private val callerNumber = MagicLiveHandshake.normalizeCallerNumber(caller.number)
        private val timer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "gemini-live-timeout-$id").apply { isDaemon = true }
        }

        fun start(token: String) {
            transition(LegState.PROCEEDING)
            transition(LegState.RINGING)
            val protocols = try { MagicLiveHandshake.protocols(token) } catch (_: Exception) {
                fail(HangupCause.UNAVAILABLE, "invalid agent device token")
                return
            }
            val request = try {
                val builder = Request.Builder()
                    .url(MagicLiveHandshake.URL)
                    .header("Origin", MagicLiveHandshake.ORIGIN)
                    .header("Sec-WebSocket-Protocol", protocols.joinToString(", "))
                callerNumber?.let { builder.header("X-Magic-Phone-Caller", it) }
                builder.build()
            } catch (t: Throwable) {
                fail(HangupCause.UNAVAILABLE, "invalid WebSocket configuration")
                return
            }
            setupTimeout = timer.schedule({
                if (!ended && state != LegState.CONNECTED) {
                    fail(HangupCause.NO_ANSWER, "Gemini setup timed out")
                }
            }, 20, TimeUnit.SECONDS)
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (ended) webSocket.close(1000, "call ended")
                    else log("Gemini Live WebSocket opened for call leg $id")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (ended) return
                    runCatching { handleMessage(text) }.onFailure {
                        log("Gemini Live message parse failed (${it.javaClass.simpleName})")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!ended) {
                        log("Gemini Live WebSocket failed: type=${t.javaClass.simpleName} http=${response?.code ?: 0}")
                        fail(HangupCause.NETWORK_ERROR, "Gemini connection failed")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!ended) fail(HangupCause.NETWORK_ERROR, "Gemini connection closed ($code)")
                }
            })
        }

        private fun handleMessage(text: String) {
            val root = JSONObject(text)
            if (root.optString("bridgeStatus") == "setup_confirmed") {
                if (state != LegState.CONNECTED) {
                    val port = GeminiLiveMediaPort(format, diagnostic = { log(it) }) { encodedAudio ->
                        val ws = socket
                        if (ws == null || ended || ws.queueSize() > 256 * 1024) false
                        else ws.send(JSONObject().put("type", "audio").put("data", encodedAudio).toString())
                    }
                    mediaPort = port
                    setupTimeout?.cancel(false)
                    setupTimeout = null
                    if (transition(LegState.CONNECTED)) {
                        publishMedia(port)
                        val started = socket?.send(JSONObject().put("type", "phone_start").toString()) == true
                        if (!started) fail(HangupCause.NETWORK_ERROR, "could not start phone greeting")
                        else log("Gemini Live ready; call leg connected")
                    } else port.close()
                }
                return
            }
            if (root.optString("bridgeStatus") == "audio_forwarded") {
                val forwarded = root.optLong("audioFrames", 0L)
                if (forwarded > 0 && forwarded % 250L == 0L) {
                    log("Origin forwarded " + forwarded + " audio frames toward Gemini")
                }
                return
            }
            if (root.has("bridgeError")) {
                val error = root.optString("bridgeError", "bridge_error")
                val phase = root.optString("phase", "")
                val code = root.optInt("closeCode", 0)
                log("Gemini Live bridge error: $error phase=$phase code=$code")
                fail(HangupCause.NETWORK_ERROR, "Gemini bridge error")
                return
            }

            // Transcriptions can arrive as top-level Live messages without serverContent.
            val server = root.optJSONObject("serverContent") ?: JSONObject()
            if (server.optBoolean("interrupted", false)) {
                mediaPort?.interrupt()
                goodbyeGate.onInterrupted()
                cancelPendingHangup("caller interrupted the farewell")
            }

            val parts = server.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) goodbyeGate.onAssistantTurnStarted()
            observeTranscriptions(root, server)

            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data") ?: continue
                    val mime = inline.optString("mimeType", inline.optString("mime_type", "audio/pcm;rate=24000"))
                    if (!mime.startsWith("audio/pcm")) continue
                    val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 24000
                    if (rate != 24000) {
                        log("Unsupported Gemini audio sample rate: $rate")
                        fail(HangupCause.INTERNAL_ERROR, "unsupported Gemini audio format")
                        return
                    }
                    val encoded = inline.optString("data")
                    if (encoded.isNotEmpty()) mediaPort?.onGeminiPcm24k(Base64.getDecoder().decode(encoded))
                }
            }

            if (server.optBoolean("turnComplete", false)) {
                mediaPort?.endTurn()
                if (goodbyeGate.onAssistantTurnCompleted()) scheduleHangupAfterPlayback()
            }
            if (goodbyeGate.consumeReadyAfterTranscript()) scheduleHangupAfterPlayback()
        }

        private fun observeTranscriptions(root: JSONObject, server: JSONObject) {
            val input = root.optJSONObject("inputTranscription") ?: server.optJSONObject("inputTranscription")
            if (input != null && isFinalTranscription(input)) {
                val spoken = input.optString("text").trim()
                if (spoken.isNotEmpty()) {
                    // Any new caller turn supersedes a pending automatic hangup.
                    cancelPendingHangup("caller spoke again")
                    goodbyeGate.onCallerTranscript(spoken)
                    if (CallEndIntentDetector.isCallerGoodbye(spoken)) {
                        log("Caller goodbye detected; waiting for the final Gemini response")
                    } else if (CallEndIntentDetector.isCallerEndCallCommand(spoken)) {
                        log("Caller requested call termination; waiting for Gemini's farewell response")
                    }
                }
            }

            val output = root.optJSONObject("outputTranscription") ?: server.optJSONObject("outputTranscription")
            if (output != null && isFinalTranscription(output)) {
                val spoken = output.optString("text").trim()
                if (spoken.isNotEmpty()) {
                    goodbyeGate.onAssistantTranscript(spoken)
                    if (CallEndIntentDetector.isAssistantFarewell(spoken)) {
                        log("Assistant farewell detected; waiting for playback completion")
                    }
                }
            }
        }

        private fun isFinalTranscription(value: JSONObject): Boolean =
            !value.has("finished") || value.optBoolean("finished", false)

        private fun scheduleHangupAfterPlayback() {
            if (ended || hangupScheduled) return
            hangupScheduled = true
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)

            fun pollPlaybackQueue() {
                if (ended || !hangupScheduled) return
                if ((mediaPort?.queuedFrames() ?: 0) == 0) {
                    postHangupCheck(300) {
                        if ((mediaPort?.queuedFrames() ?: 0) == 0) {
                            hangupScheduled = false
                            endCallTask = null
                            log("Goodbye audio drained; ending Gemini Live and cellular call")
                            fail(HangupCause.NORMAL, "goodbye")
                        } else {
                            pollPlaybackQueue()
                        }
                    }
                } else if (System.nanoTime() >= deadline) {
                    hangupScheduled = false
                    endCallTask = null
                    log("Goodbye audio drain timed out; ending call safely")
                    fail(HangupCause.NORMAL, "goodbye playback timeout")
                } else {
                    postHangupCheck(40) { pollPlaybackQueue() }
                }
            }

            postHangupCheck(40) { pollPlaybackQueue() }
        }

        private fun postHangupCheck(delayMs: Long, action: () -> Unit) {
            if (ended || !hangupScheduled) return
            try {
                endCallTask = timer.schedule({
                    if (!ended && hangupScheduled) action()
                }, delayMs, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                hangupScheduled = false
            }
        }

        private fun cancelPendingHangup(reason: String) {
            if (!hangupScheduled) return
            hangupScheduled = false
            endCallTask?.cancel(false)
            endCallTask = null
            log("Automatic goodbye hangup cancelled: $reason")
        }

        fun fail(cause: HangupCause, safeReason: String) {
            if (ended) return
            ended = true
            setupTimeout?.cancel(false)
            setupTimeout = null
            hangupScheduled = false
            endCallTask?.cancel(false)
            endCallTask = null
            timer.shutdownNow()
            mediaPort?.close()
            socket?.close(1000, safeReason)
            if (transition(LegState.ENDED, cause)) {
                publishMedia(null)
                remove(this)
                log("Gemini Live call leg ended: $cause ($safeReason)")
            }
        }

        override fun ring() = Unit
        override fun answer() = Unit
        override fun hangup(cause: HangupCause) = fail(cause, "call ended")
        override fun sendDtmf(digit: Char, durationMs: Int) = Unit
    }
}


/** Direct-origin-only handshake with no alternate endpoint. */
internal object MagicLiveHandshake {
    const val URL = "wss://voice.magics.site/ws?mode=phone"
    const val ORIGIN = "https://ai.magics.site"
    const val PROTOCOL = "magic-phone.v1"
    private val deviceTokenPattern = Regex("^mpa_[a-f0-9]{48}$")

    fun isDeviceToken(secret: String?): Boolean = secret != null && deviceTokenPattern.matches(secret)

    /** Telecom remote-party number is passed only over the authenticated TLS WebSocket. */
    fun normalizeCallerNumber(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val international = raw.startsWith("+") || raw.startsWith("00")
        var digits = raw.filter { it in '0'..'9' }
        if (raw.startsWith("00") && digits.startsWith("00")) digits = digits.drop(2)
        if (digits.length !in 7..15) return null
        return (if (international) "+" else "") + digits
    }

    fun protocols(secret: String): List<String> {
        require(isDeviceToken(secret)) { "invalid device token" }
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(secret.toByteArray(Charsets.UTF_8))
        return listOf(PROTOCOL, "magic-agent.$encoded")
    }
}
