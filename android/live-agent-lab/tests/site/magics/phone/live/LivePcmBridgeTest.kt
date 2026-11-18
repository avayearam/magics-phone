package site.magics.phone.live
import java.util.Base64

fun main() {
    var checks = 0
    fun ok(value: Boolean, msg: String) { checks++; check(value) { msg } }
    val sent = mutableListOf<String>()
    val port = LivePcmBridge { msg -> sent += msg; true }
    ok(port.nextTelephoneFrame().all { it == 0.toShort() }, "starts silent")
    ok(port.sendTelephoneFrame16k(ShortArray(320) { 1234 }), "audio forwarded")
    ok(sent.size == 1 && sent[0].startsWith("{\"type\":\"audio\",\"data\":\""), "wire shape")
    val payload = sent[0].substringAfter("\"data\":\"").substringBefore("\"")
    val wire = Base64.getDecoder().decode(payload)
    ok(wire.size == 640 && wire[0] == 0xd2.toByte() && wire[1] == 0x04.toByte(), "PCM LE 1234")
    val pcm24 = ByteArray(480 * 2) { index -> if (index % 2 == 0) 0x39 else 0x30 }
    port.onGeminiAudio24k(pcm24.copyOfRange(0, 202))
    port.onGeminiAudio24k(pcm24.copyOfRange(202, pcm24.size))
    ok(port.queuedSamples() == 320, "resampled 480 to 320 across split")
    ok(port.nextTelephoneFrame().all { it == 12345.toShort() }, "response PCM")
    ok(port.queuedSamples() == 0, "consumed frame")
    repeat(250) { port.onGeminiAudio24k(pcm24) }
    ok(port.queuedSamples() <= 320 * 50, "bounded buffer")
    port.interrupt()
    ok(port.queuedSamples() == 0, "interrupt clears audio")
    port.end()
    ok(!port.sendTelephoneFrame16k(ShortArray(320)), "stop prevents outgoing")
    ok(port.nextTelephoneFrame().all { it == 0.toShort() }, "stop yields silence")
    val secret = "a/b+=-customer_token_1234567890"
    val protocols = MagicLiveHandshake.protocols(secret)
    ok(protocols.first() == "magic-phone.v1", "version")
    ok(String(Base64.getUrlDecoder().decode(protocols.last().removePrefix("magic-auth."))) == secret, "token roundtrip")
    ok(!protocols.last().contains('/'), "URL-safe header")
    println("PASS: " + checks + " PCM/WSS lab checks")
}
