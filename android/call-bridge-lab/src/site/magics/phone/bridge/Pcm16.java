package site.magics.phone.bridge;

/** PCM helpers for the phone-only audio lab. */
public final class Pcm16 {
    public static final int SAMPLE_RATE = 48_000;
    public static final int FRAME_MS = 20;
    public static final int FRAMES_PER_PACKET = SAMPLE_RATE * FRAME_MS / 1000;
    public static final int MONO_PACKET_BYTES = FRAMES_PER_PACKET * 2;
    public static final int STEREO_PACKET_BYTES = MONO_PACKET_BYTES * 2;

    private Pcm16() {}

    /** Downmix interleaved little-endian PCM16 stereo to mono.
     * Does NOT isolate either voice in a mixed call capture.
     */
    public static int stereoToMono(byte[] src, int len, byte[] dst) {
        if (src == null || dst == null || len < 0 || len > src.length || len % 4 != 0
                || dst.length < len / 2) {
            throw new IllegalArgumentException("invalid interleaved stereo PCM buffer");
        }
        for (int i = 0, j = 0; i < len; i += 4, j += 2) {
            int left = (short) (((src[i + 1] & 0xff) << 8) | (src[i] & 0xff));
            int right = (short) (((src[i + 3] & 0xff) << 8) | (src[i + 2] & 0xff));
            int mean = (left + right) / 2;
            dst[j] = (byte) mean;
            dst[j + 1] = (byte) (mean >> 8);
        }
        return len / 2;
    }

    public static short readMonoSample(byte[] buffer, int offset) {
        if (buffer == null || offset < 0 || offset + 1 >= buffer.length) {
            throw new IllegalArgumentException("sample outside buffer");
        }
        return (short) (((buffer[offset + 1] & 0xff) << 8) | (buffer[offset] & 0xff));
    }
}
