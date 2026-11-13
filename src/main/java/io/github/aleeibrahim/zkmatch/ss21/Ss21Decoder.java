package io.github.aleeibrahim.zkmatch.ss21;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Reads the minutiae out of a ZK 10.0 fingerprint template ("SS21" format).
 *
 * <p>The layout was worked out by comparing templates of known captures (black-box
 * observation only); it is not taken from any vendor code or documentation:
 *
 * <pre>
 *   0..1    varies
 *   2..5    "SS21"
 *   8..9    total length, big-endian (equals the size in most templates; not checked)
 *   11..17  constant 04 08 05 07 09 CE D0 (other values: an unsupported variant)
 *   18..    XOR-masked by absolute position mod 10, see {@link #unmask(byte[])}
 *   20..21  image width, little-endian (after unmasking)
 *   22..23  image height, little-endian
 *   29      minutiae count N
 *   30..    N records of 6 bytes: x u16 LE, y u16 LE, angle u8 (x 360/256 degrees), flags u8
 * </pre>
 *
 * Bytes after the minutiae records are not interpreted.
 */
public final class Ss21Decoder {
    /** Offset of the "SS21" magic. */
    public static final int MAGIC_OFFSET = 2;
    public static final byte[] MAGIC = "SS21".getBytes(StandardCharsets.US_ASCII);
    /** Bytes 11..17 in every supported template. */
    static final byte[] CONSTANT_11_17 = {0x04, 0x08, 0x05, 0x07, 0x09, (byte) 0xCE, (byte) 0xD0};
    /** The XOR mask starts at this offset. */
    public static final int MASK_START = 18;
    public static final int COUNT_OFFSET = 29;
    public static final int RECORDS_OFFSET = 30;
    public static final int RECORD_SIZE = 6;

    private Ss21Decoder() {}

    /** Decodes a base64 template, as ZKBio CVSecurity and the ZK enrollment helper hand it out. */
    public static Ss21Template decodeBase64(String base64) {
        if (base64 == null || base64.isBlank()) throw new Ss21Exception(Ss21Exception.Reason.EMPTY, "template is empty");
        byte[] raw;
        try {
            raw = Base64.getMimeDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new Ss21Exception(Ss21Exception.Reason.NOT_BASE64, "template is not valid base64");
        }
        return decode(raw);
    }

    public static Ss21Template decode(byte[] raw) {
        if (raw == null || raw.length == 0) throw new Ss21Exception(Ss21Exception.Reason.EMPTY, "template is empty");
        if (raw.length < RECORDS_OFFSET) {
            throw new Ss21Exception(Ss21Exception.Reason.TOO_SHORT, "template has " + raw.length + " bytes, fewer than the " + RECORDS_OFFSET + "-byte header");
        }
        if (!Arrays.equals(raw, MAGIC_OFFSET, MAGIC_OFFSET + 4, MAGIC, 0, 4)) {
            throw new Ss21Exception(Ss21Exception.Reason.NOT_SS21, "no SS21 magic at bytes 2..5; not a ZK 10.0 SS21 template (other ZK 10.0 variants and 12.0/13.0 are not supported)");
        }
        if (!Arrays.equals(raw, 11, 18, CONSTANT_11_17, 0, CONSTANT_11_17.length)) {
            throw new Ss21Exception(Ss21Exception.Reason.UNSUPPORTED_VARIANT, "SS21 template with unknown header constants at bytes 11..17; this variant is not supported");
        }
        byte[] d = unmask(raw);
        int width = u16le(d, 20);
        int height = u16le(d, 22);
        if (width < 64 || width > 2048 || height < 64 || height > 2048) {
            throw new Ss21Exception(Ss21Exception.Reason.UNSUPPORTED_VARIANT, "implausible image size " + width + "x" + height + "; this variant is not supported");
        }
        int n = d[COUNT_OFFSET] & 0xFF;
        if (n == 0) throw new Ss21Exception(Ss21Exception.Reason.NO_MINUTIAE, "template has no minutiae");
        if (RECORDS_OFFSET + RECORD_SIZE * n > d.length) {
            throw new Ss21Exception(Ss21Exception.Reason.TRUNCATED, "template says " + n + " minutiae but has room for " + (d.length - RECORDS_OFFSET) / RECORD_SIZE);
        }
        List<Ss21Minutia> minutiae = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            int o = RECORDS_OFFSET + RECORD_SIZE * j;
            minutiae.add(new Ss21Minutia(u16le(d, o), u16le(d, o + 2), d[o + 4] & 0xFF, d[o + 5] & 0xFF));
        }
        return new Ss21Template(width, height, headerVariant(width, height), List.copyOf(minutiae));
    }

    /**
     * Removes the position-keyed XOR mask from byte 18 on. The key depends on the absolute
     * position mod 10: residue 1 uses the template's own length low byte (byte 9), residue 0
     * uses 0x04, residue 8 uses 0xC4, and all other residues are unmasked.
     * The mask is its own inverse, so this also applies it.
     */
    public static byte[] unmask(byte[] raw) {
        byte[] d = raw.clone();
        int lengthLow = raw.length > 9 ? raw[9] & 0xFF : 0;
        for (int i = MASK_START; i < d.length; i++) {
            d[i] = (byte) (d[i] ^ maskByte(i, lengthLow));
        }
        return d;
    }

    /** The mask byte at absolute position {@code i} (only meaningful for i >= 18). */
    public static int maskByte(int i, int lengthLow) {
        return switch (i % 10) {
            case 0 -> 0x04;
            case 1 -> lengthLow;
            case 8 -> 0xC4;
            default -> 0;
        };
    }

    /**
     * Templates seen so far come in a few sensor geometries: 280x361 from the USB ZK9500
     * enrollment reader, and about 299x374 or 287x361 from wall readers. All decode the same
     * way; the label is for statistics only.
     */
    static String headerVariant(int width, int height) {
        return width + "x" + height;
    }

    static int u16le(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8;
    }
}
