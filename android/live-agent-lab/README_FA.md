# Magic Phone Android — Direct Gemini Live transport lab

Phase 1: standalone phone PCM ↔ WebSocket protocol preparation; NOT a built APK and NOT connected to an actual cellular call. Existing Magic Phone Android app is unaffected.

- Phone target: Samsung Galaxy S23 Ultra / Irancell / Android 16.
- Voice SIP Agent was installed with Shizuku access, but SIP registration failed with 408. The new design bypasses SIP and PBX.
- Working Gemini endpoint: wss://phone.magics.site/ws ; the Worker holds the GEMINI_API_KEY. On Android, only BRIDGE_ACCESS_TOKEN is needed (store via Android Keystore; never commit it).
- Voice SIP Agent source: https://github.com/sparkcyf/android-voice-sip-agent (GPL-2.0-or-later). Its CallBridge can use a custom CallEndpoint instead of the SIP leg. Respect GPL licensing and notices.
- LivePcmBridge converts 16kHz 20ms mono PCM frames to the existing Worker audio JSON and 24kHz Gemini PCM reply into buffered 16kHz 20ms frames; supports interruption and bounded queue.
- MagicLiveHandshake generates the two subprotocols accepted by the currently working Worker.

Offline tests:
    bash android/live-agent-lab/run-tests.sh

## Next gates — mandatory before claiming cellular success
1. Use Voice SIP Agent's official loopback mode on the real S23 Ultra to verify privileged *two-way in-call audio*. Keep Take over calls OFF until a scheduled, consenting test call. Use Direct telephony; disable local output mute when testing.
2. Implement authenticated Android WebSocket client and a new CallEndpoint in a GPL-compatible Voice SIP Agent fork; wire it to CallBridge and Android telephony, while retaining original system dialer by default.
3. Build and sign a test APK, validate it on Android 16/Irancell, test call teardown, latency, echo and secure token storage.

Optional ADB command for offline loopback configuration:
    adb shell am broadcast -n io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver --es op loopback --ez on true
    adb shell am broadcast -n io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver --es op provision --ei audio_method 2
    adb shell am broadcast -n io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver --es op earpiece-mute --ez on false

Turn Take over calls ON only for the coordinated test, then immediately back OFF. No call interception or recording is enabled by the lab files. For the loopback to work, the app must be able to intercept the test call on this specific firmware.
