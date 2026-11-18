package site.magics.phone.bridge;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Versioned audio framing. Codec only; it does not open a network socket. */
public final class AudioFrame {
    public static final int MAGIC = 0x4d504231; // MPB1
    public static final int VERSION = 1;
    public static final int CAPTURE = 1;
    public static final int INJECT = 2;
    public static final int CALL_ENDED = 3;
    private static final int MAX_PAYLOAD = Pcm16.STEREO_PACKET_BYTES;

    public final int type;
    public final long sequence;
    public final byte[] payload;

    public AudioFrame(int type, long sequence, byte[] payload) {
        if (type < CAPTURE || type > CALL_ENDED || sequence < 0 || payload == null
                || payload.length > MAX_PAYLOAD || (type == CALL_ENDED && payload.length != 0)
                || (type != CALL_ENDED && (payload.length == 0 || payload.length % 2 != 0))) {
            throw new IllegalArgumentException("invalid audio frame");
        }
        this.type = type;
        this.sequence = sequence;
        this.payload = payload.clone();
    }

    public static void write(OutputStream stream, AudioFrame frame) throws IOException {
        if (stream == null || frame == null) throw new IllegalArgumentException("missing frame/stream");
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(frame.type);
        out.writeLong(frame.sequence);
        out.writeInt(frame.payload.length);
        out.write(frame.payload);
        out.flush();
    }

    /** Rejects oversized or malformed frames before any payload allocation. */
    public static AudioFrame read(InputStream stream) throws IOException {
        if (stream == null) throw new IllegalArgumentException("missing stream");
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) {
            throw new IOException("unsupported audio bridge frame");
        }
        int type = in.readUnsignedByte();
        long sequence = in.readLong();
        int len = in.readInt();
        if (type < CAPTURE || type > CALL_ENDED || sequence < 0 || len < 0
                || len > MAX_PAYLOAD || (type == CALL_ENDED && len != 0)
                || (type != CALL_ENDED && (len == 0 || len % 2 != 0))) {
            throw new IOException("invalid audio bridge frame header");
        }
        byte[] data = new byte[len];
        try {
            in.readFully(data);
        } catch (EOFException e) {
            throw new EOFException("truncated audio payload");
        }
        return new AudioFrame(type, sequence, data);
    }
}
