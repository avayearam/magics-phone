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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A direct Gemini Live call endpoint. It replaces the SIP side of CallBridge;
 * the cellular endpoint and its Shizuku audio path remain unchanged.
 *
 * The phone sends only BRIDGE_ACCESS_TOKEN. GEMINI_API_KEY remains in Cloudflare.
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
    @Volatile private var lastStatus = EndpointStatus(false, "bridge token not configured")

    override val status: EndpointStatus
        get() {
            if (closed) return EndpointStatus(false, "stopped")
            val token = runCatching { tokenProvider() }.getOrNull()
            return if (validToken(token)) EndpointStatus(true, "Gemini Live bridge configured")
            else EndpointStatus(false, "bridge token not configured")
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
            leg.fail(HangupCause.UNAVAILABLE, "bridge token missing")
            updateStatus(EndpointStatus(false, "bridge token not configured"))
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

    private fun validToken(value: String?): Boolean =
        !value.isNullOrBlank() && value.length >= 24 && value.none { it.code < 32 || it.code == 127 }

    private inner class GeminiLeg(
        id: String,
        target: String,
        caller: Party,
        line: Int?,
    ) : AbstractCallLeg(id, "gemini-live", LegDirection.OUTBOUND, caller, target, line) {
        @Volatile private var socket: WebSocket? = null
        @Volatile private var mediaPort: GeminiLiveMediaPort? = null
        @Volatile private var ended = false
        private val timer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "gemini-live-timeout-$id").apply { isDaemon = true }
        }

        fun start(token: String) {
            transition(LegState.PROCEEDING)
            transition(LegState.RINGING)
            val encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(token.toByteArray(Charsets.UTF_8))
            val request = try {
                Request.Builder()
                    .url("wss://phone.magics.site/ws?mode=phone")
                    .header("Origin", "https://ai.magics.site")
                    .header("Sec-WebSocket-Protocol", "magic-phone.v1, magic-auth.$encoded")
                    .build()
            } catch (t: Throwable) {
                fail(HangupCause.UNAVAILABLE, "invalid WebSocket configuration")
                return
            }
            timer.schedule({
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
                        log("Gemini Live message parse failed (\${it.javaClass.simpleName})")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!ended) {
                        log("Gemini Live WebSocket failed: \${t.javaClass.simpleName}")
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
                    val port = GeminiLiveMediaPort(format) { encodedAudio ->
                        val ws = socket
                        if (ws == null || ended || ws.queueSize() > 256 * 1024) false
                        else ws.send(JSONObject().put("type", "audio").put("data", encodedAudio).toString())
                    }
                    mediaPort = port
                    timer.shutdownNow()
                    if (transition(LegState.CONNECTED)) {
                        publishMedia(port)
                        val started = socket?.send(JSONObject().put("type", "phone_start").toString()) == true
                        if (!started) fail(HangupCause.NETWORK_ERROR, "could not start phone greeting")
                        else log("Gemini Live ready; call leg connected")
                    } else port.close()
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
            val server = root.optJSONObject("serverContent") ?: return
            if (server.optBoolean("interrupted", false)) mediaPort?.interrupt()
            val parts = server.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
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

        fun fail(cause: HangupCause, safeReason: String) {
            if (ended) return
            ended = true
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
