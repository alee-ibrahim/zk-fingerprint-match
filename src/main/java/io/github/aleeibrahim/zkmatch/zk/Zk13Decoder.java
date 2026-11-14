package io.github.aleeibrahim.zkmatch.zk;

import io.github.aleeibrahim.zkmatch.ss21.Ss21Exception;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Minutia;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Template;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the minutiae out of a ZK 13.0 fingerprint template.
 *
 * <p>The layout was worked out by comparing templates of known captures with the ZK 10.0 and
 * 12.0 templates made from the same presses (black-box observation only); it is not taken from
 * any vendor code or documentation. Every 13.0 template seen is 1552 bytes:
 *
 * <pre>
 *   0..5     6A 95 01 10 10 06
 *   6..7     vary
 *   8..12    constant 00 00 0D 00 01 (other values: an unsupported variant)
 *   13..14   vary
 *   16..     XOR-masked with the position: byte i is XORed with (i - 16) mod 256
 *   20..     up to 48 records of 24 bytes, ended early by a record with x = y = 0:
 *              x s16 LE, y s16 LE   in a frame transposed against 10.0 and scaled by 0.8
 *              angle u8             in 5-degree steps (0..71), in the 10.0 frame
 *              5 bytes              not used (one looks like a quality, three like a scale level)
 *              15 bytes             a local descriptor, not used
 *   1172..   not interpreted (an orientation grid and a 2-bit foreground mask, it seems)
 * </pre>
 *
 * <p>A record at (x13, y13) is the 10.0 minutia at about (1.25·y13, 1.25·x13) plus an offset.
 * Points just outside the image have negative coordinates, so the whole template is shifted to
 * start at 0 (SourceAFIS ignores translation). The points come from a different extractor than
 * 10.0 and 12.0: about two thirds of them coincide with 10.0 minutiae, so 13.0 templates match
 * live images with lower scores than 10.0 or 12.0 do.
 */
public final class Zk13Decoder {
    public static final byte[] MAGIC = {0x6A, (byte) 0x95, 0x01, 0x10, 0x10, 0x06};
    static final byte[] CONSTANT_8_12 = {0x00, 0x00, 0x0D, 0x00, 0x01};
    public static final int MASK_START = 16;
    public static final int RECORDS_OFFSET = 20;
    public static final int RECORD_SIZE = 24;
    public static final int MAX_RECORDS = 48;
    /** 13.0 coordinates times this give 10.0 (500 dpi) pixels. */
    public static final double SCALE = 1.25;
    /** Angle steps in a full turn. */
    public static final int ANGLE_STEPS = 72;

    private Zk13Decoder() {}

    /** Whether {@code raw} starts with the 13.0 magic. */
    public static boolean matches(byte[] raw) {
        return raw != null && raw.length >= MAGIC.length && Arrays.equals(raw, 0, MAGIC.length, MAGIC, 0, MAGIC.length);
    }

    public static Ss21Template decode(byte[] raw) {
        if (raw == null || raw.length == 0) throw new Ss21Exception(Ss21Exception.Reason.EMPTY, "template is empty");
        if (raw.length < RECORDS_OFFSET + RECORD_SIZE) {
            throw new Ss21Exception(Ss21Exception.Reason.TOO_SHORT, "template has " + raw.length + " bytes, fewer than a 13.0 header and one record");
        }
        if (!matches(raw)) throw new Ss21Exception(Ss21Exception.Reason.NOT_ZK13, "no ZK 13.0 magic at bytes 0..5");
        if (!Arrays.equals(raw, 8, 13, CONSTANT_8_12, 0, CONSTANT_8_12.length)) {
            throw new Ss21Exception(Ss21Exception.Reason.UNSUPPORTED_VARIANT, "ZK 13.0 template with unknown header constants at bytes 8..12; this variant is not supported");
        }
        byte[] d = unmask(raw);
        List<int[]> points = new ArrayList<>(MAX_RECORDS);
        int minX = 0, minY = 0;
        for (int k = 0; k < MAX_RECORDS; k++) {
            int o = RECORDS_OFFSET + RECORD_SIZE * k;
            if (o + RECORD_SIZE > d.length) break;
            int x13 = s16le(d, o), y13 = s16le(d, o + 2);
            if (x13 == 0 && y13 == 0) break;
            // Transpose and scale into the 10.0 frame.
            int x = (int) Math.round(SCALE * y13), y = (int) Math.round(SCALE * x13);
            int angle = Math.floorMod((int) Math.round((d[o + 4] & 0xFF) * 256.0 / ANGLE_STEPS), 256);
            points.add(new int[]{x, y, angle});
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
        }
        if (points.isEmpty()) throw new Ss21Exception(Ss21Exception.Reason.NO_MINUTIAE, "template has no minutiae");
        List<Ss21Minutia> minutiae = new ArrayList<>(points.size());
        int width = 0, height = 0;
        for (int[] p : points) {
            int x = p[0] - minX, y = p[1] - minY;
            minutiae.add(new Ss21Minutia(x, y, p[2], 0));
            width = Math.max(width, x + 1);
            height = Math.max(height, y + 1);
        }
        return new Ss21Template(width, height, "zk13", List.copyOf(minutiae));
    }

    /** Removes (or applies; it is its own inverse) the position mask from byte 16 on. */
    public static byte[] unmask(byte[] raw) {
        byte[] d = raw.clone();
        for (int i = MASK_START; i < d.length; i++) d[i] = (byte) (d[i] ^ (i - MASK_START));
        return d;
    }

    static int s16le(byte[] b, int o) {
        return (short) ((b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8);
    }
}
