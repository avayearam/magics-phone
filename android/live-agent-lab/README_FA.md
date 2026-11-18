# Magic Phone Android — Direct Gemini Live transport lab

Milestone 1 complete: the real Irancell incoming-call echo test passed on the Samsung Galaxy S23 Ultra using Voice SIP Agent. Gemini Live transport is now integrated as a pinned upstream submodule plus a local overlay under `android/voice-sip-agent-overlay/`. The APK has not yet been built.

- Phone target: Samsung Galaxy S23 Ultra / Irancell / Android 16.
- Voice SIP Agent was installed with Shizuku access, but SIP registration failed with 408. The new design bypasses SIP and PBX.
- Working Gemini endpoint: wss://phone.magics.site/ws ; the Worker holds the GEMINI_API_KEY. On Android, only BRIDGE_ACCESS_TOKEN is needed (store via Android Keystore; never commit it).
- Voice SIP Agent source: https://github.com/sparkcyf/android-voice-sip-agent (GPL-2.0-or-later). Its CallBridge can use a custom CallEndpoint instead of the SIP leg. Respect GPL licensing and notices.
- LivePcmBridge converts 16kHz 20ms mono PCM frames to the existing Worker audio JSON and 24kHz Gemini PCM reply into buffered 16kHz 20ms frames; supports interruption and bounded queue.
- MagicLiveHandshake generates the two subprotocols accepted by the currently working Worker.

Offline tests:
    bash android/live-agent-lab/run-tests.sh

## Next gates — mandatory before claiming cellular success
1. Initialize the pinned submodule and apply `android/voice-sip-agent-overlay/apply.sh` (or `apply.ps1` on Windows).
2. Build the Android app and install it alongside the original Voice SIP Agent; grant Shizuku to the new package.
3. Enter the existing Worker token, enable Gemini Live for a coordinated test, and validate both audio directions on the S23 Ultra.

The user completed the loopback test directly in the Android app; no computer or ADB was required. The caller heard their own voice echoed, confirming cellular capture and injection on this device.

Optional ADB command for offline loopback configuration (not required for the completed test):
    adb shell am broadcast -n io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver --es op loopback --ez on true
    adb shell am broadcast -n io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver --es op provision --ei audio_method 2
    adb shell am broadcast -n io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver --es op earpiece-mute --ez on false

Turn Take over calls ON only for the coordinated test, then immediately back OFF. No call interception or recording is enabled by the lab files. For the loopback to work, the app must be able to intercept the test call on this specific firmware.
