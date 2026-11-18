package site.magics.phone.bridge;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

/**
 * EXPERIMENTAL privileged Android-shell call-audio endpoint.
 * Normal APK processes lack signature-level VOICE_CALL/uplink permissions.
 * Must not be started from the ordinary Magic Phone Activity.
 * No auto-answer, background listening, server or connection is included.
 * Technical reference: JemRec (Apache-2.0), see README_FA.md.
 */
public final class AndroidCallAudioEndpoint implements AutoCloseable {
    public enum UplinkRoute { IN_CALL_MUSIC, CALL_SCREENING }

    private final CallSessionGate gate;
    private AudioRecord record;
    private AudioTrack track;

    public AndroidCallAudioEndpoint(CallSessionGate gate) {
        if (gate == null) throw new IllegalArgumentException("missing session gate");
        this.gate = gate;
    }

    /** Requires a user-armed, active, consented call and a privileged shell uid. */
    public synchronized void start(AudioManager manager, UplinkRoute route) throws Exception {
        if (manager == null || route == null || !gate.canStream() || record != null || track != null) {
            throw new IllegalStateException("disabled, already running, or missing audio route");
        }
        try {
            AudioFormat stereoIn = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Pcm16.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int captureMin = AudioRecord.getMinBufferSize(Pcm16.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (captureMin < 0) throw new IllegalStateException("VOICE_CALL capture not supported");
            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
                    .setAudioFormat(stereoIn)
                    .setBufferSizeInBytes(Math.max(captureMin, Pcm16.STEREO_PACKET_BYTES * 4))
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("could not initialize VOICE_CALL capture");
            }
            track = route == UplinkRoute.IN_CALL_MUSIC
                    ? createMusicTrack(manager) : createScreeningTrack(manager);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("uplink AudioTrack did not initialize");
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("VOICE_CALL source refused recording");
            }
            track.play();
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private static AudioFormat monoOut() {
        return new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Pcm16.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
    }

    private static AudioTrack createMusicTrack(AudioManager manager) {
        AudioDeviceInfo uplink = null;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_TELEPHONY) uplink = device;
        }
        if (uplink == null) throw new IllegalStateException("no telephony uplink device");
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(monoOut())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (!t.setPreferredDevice(uplink)) {
            t.release();
            throw new IllegalStateException("permission or route refused uplink playback");
        }
        return t;
    }

    /** Hidden/SystemApi; may be missing or blocked by device/ROM. */
    private static AudioTrack createScreeningTrack(AudioManager manager) throws Exception {
        return (AudioTrack) AudioManager.class
                .getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class)
                .invoke(manager, monoOut());
    }

    /** Read up to one 20ms stereo call-audio packet. */
    public synchronized int readCall(byte[] dst) {
        if (!gate.canStream() || record == null || dst == null
                || dst.length < Pcm16.STEREO_PACKET_BYTES) return -1;
        return record.read(dst, 0, Pcm16.STEREO_PACKET_BYTES, AudioRecord.READ_BLOCKING);
    }

    /** A successful write does NOT prove the remote party heard the audio. */
    public synchronized int writeToCaller(byte[] monoPacket) {
        if (!gate.canStream() || track == null || monoPacket == null
                || monoPacket.length != Pcm16.MONO_PACKET_BYTES) return -1;
        int written = 0;
        while (written < monoPacket.length && gate.canStream()) {
            int n = track.write(monoPacket, written, monoPacket.length - written);
            if (n <= 0) return -1;
            written += n;
        }
        return written;
    }

    @Override
    public synchronized void close() {
        AudioTrack outgoing = track;
        track = null;
        if (outgoing != null) {
            try { outgoing.pause(); } catch (RuntimeException ignored) {}
            try { outgoing.flush(); } catch (RuntimeException ignored) {}
            outgoing.release();
        }
        AudioRecord incoming = record;
        record = null;
        if (incoming != null) {
            try { incoming.stop(); } catch (IllegalStateException ignored) {}
            incoming.release();
        }
        gate.stop();
    }
}
