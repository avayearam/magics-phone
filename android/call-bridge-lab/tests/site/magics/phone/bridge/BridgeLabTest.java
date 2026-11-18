package site.magics.phone.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

public final class BridgeLabTest {
    private static int checks;
    private static void ok(boolean yes, String why) {
        checks++;
        if (!yes) throw new AssertionError(why);
    }
    private static void throwsError(Class<? extends Exception> type, Throwing task) {
        checks++;
        try { task.run(); }
        catch (Exception e) {
            if (type.isInstance(e)) return;
            throw new AssertionError("expected " + type + " but got " + e, e);
        }
        throw new AssertionError("expected " + type.getName());
    }
    private interface Throwing { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        ok(Pcm16.FRAMES_PER_PACKET == 960, "20ms at 48kHz");
        ok(Pcm16.MONO_PACKET_BYTES == 1920, "mono PCM packet size");
        ok(Pcm16.STEREO_PACKET_BYTES == 3840, "stereo PCM packet size");
        byte[] stereo = {(byte)0xff, 0x7f, (byte)0xff, 0x7f, 0x00, (byte)0x80, 0x00, (byte)0x80};
        byte[] mono = new byte[4];
        ok(Pcm16.stereoToMono(stereo, stereo.length, mono) == 4, "downmix length");
        ok(Pcm16.readMonoSample(mono, 0) == Short.MAX_VALUE, "positive headroom");
        ok(Pcm16.readMonoSample(mono, 2) == Short.MIN_VALUE, "negative headroom");
        throwsError(IllegalArgumentException.class, () -> Pcm16.stereoToMono(stereo, 6, mono));
        throwsError(IllegalArgumentException.class, () -> Pcm16.stereoToMono(stereo, 8, new byte[2]));
        for (int kind : new int[]{AudioFrame.CAPTURE, AudioFrame.INJECT, AudioFrame.CALL_ENDED}) {
            AudioFrame input = new AudioFrame(kind, 42, kind == AudioFrame.CALL_ENDED ? new byte[0] : mono);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            AudioFrame.write(buf, input);
            AudioFrame decoded = AudioFrame.read(new ByteArrayInputStream(buf.toByteArray()));
            ok(decoded.sequence == 42 && decoded.type == kind
                    && Arrays.equals(input.payload, decoded.payload), "frame type " + kind);
        }
        throwsError(IllegalArgumentException.class, () -> new AudioFrame(AudioFrame.CAPTURE, -1, mono));
        throwsError(IllegalArgumentException.class, () -> new AudioFrame(AudioFrame.INJECT, 0, new byte[3842]));
        throwsError(IllegalArgumentException.class, () -> new AudioFrame(AudioFrame.CALL_ENDED, 0, mono));
        byte[] truncated = new byte[19];
        ByteArrayOutputStream valid = new ByteArrayOutputStream();
        AudioFrame.write(valid, new AudioFrame(AudioFrame.CAPTURE, 1, mono));
        System.arraycopy(valid.toByteArray(), 0, truncated, 0, truncated.length);
        throwsError(EOFException.class, () -> AudioFrame.read(new ByteArrayInputStream(truncated)));
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bad);
        out.writeInt(AudioFrame.MAGIC); out.writeByte(AudioFrame.VERSION); out.writeByte(AudioFrame.CAPTURE);
        out.writeLong(0); out.writeInt(Integer.MAX_VALUE);
        throwsError(IOException.class, () -> AudioFrame.read(new ByteArrayInputStream(bad.toByteArray())));
        CallSessionGate gate = new CallSessionGate();
        ok(!gate.canStream(), "off by default");
        gate.setAudioCapable(true); gate.setCallActive(true); gate.setRemotePartyInformed(true);
        ok(!gate.canStream(), "activation required");
        gate.setUserArmed(true);
        ok(gate.canStream(), "explicit activation");
        gate.setCallActive(false);
        ok(!gate.canStream(), "revoked by call end");
        gate.setCallActive(true);
        ok(!gate.canStream(), "not auto-armed");
        gate.setUserArmed(true);
        ok(!gate.canStream(), "new call requires new notice");
        gate.setRemotePartyInformed(true);
        ok(gate.canStream(), "manual re-arm");
        gate.stop();
        ok(!gate.canStream(), "emergency stop");
        System.out.println("PASS: " + checks + " offline audio-bridge checks");
    }
}
