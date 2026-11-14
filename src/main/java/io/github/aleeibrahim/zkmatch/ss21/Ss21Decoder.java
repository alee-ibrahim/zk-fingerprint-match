package io.github.aleeibrahim.zkmatch.ss21;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the minutiae out of a ZK 10.0 ("SS21") or ZK 12.0 ("SR23") fingerprint template.
 *
 * <p>Both layouts were worked out by comparing templates of known captures (black-box
 * observation only); they are not taken from any vendor code or documentation. They differ only
 * in the magic, the constant at bytes 11..17 and two bytes of the XOR mask:
 *
 * <pre>
 *   0..1    varies
 *   2..5    "SS21" (10.0) or "SR23" (12.0)
 *   8..9    total length, big-endian (equals the size in most templates; not checked)
 *   11..17  constant, see {@link Layout} (other values: an unsupported variant)
 *   18..    XOR-masked by absolute position mod 10, see {@link #unmask(byte[], Layout)}
 *   20..21  image width, little-endian (after unmasking)
 *   22..23  image height, little-endian
 *   29      minutiae count N
 *   30..    N records of 6 bytes: x u16 LE, y u16 LE, angle u8 (x 360/256 degrees), flags u8
 * </pre>
 *
 * Bytes after the minutiae records are not interpreted. In 12.0 the flags byte looks like a
 * quality from 0 to 100; it is not used.
 */
public final class Ss21Decoder {
    /** Offset of the magic. */
    public static final int MAGIC_OFFSET = 2;
    public static final byte[] MAGIC = "SS21".getBytes(StandardCharsets.US_ASCII);
    /** The XOR mask starts at this offset. */
    public static final int MASK_START = 18;
    public static final int COUNT_OFFSET = 29;
    public static final int RECORDS_OFFSET = 30;
    public static final int RECORD_SIZE = 6;

    /** The two known layouts. */
    public enum Layout {
        /** ZK 10.0. */
        SS21("SS21", new byte[]{0x04, 0x08, 0x05, 0x07, 0x09, (byte) 0xCE, (byte) 0xD0}, 0x04, 0x00, Ss21Exception.Reason.NOT_SS21),
        /** ZK 12.0. */
        SR23("SR23", new byte[]{0x05, 0x05, 0x05, 0x07, 0x09, (byte) 0xCE, (byte) 0xD0}, 0x05, 0x01, Ss21Exception.Reason.NOT_SR23);

        final byte[] magic;
        final byte[] constant11to17;
        final int mask0, mask3;
        final Ss21Exception.Reason wrongMagic;

        Layout(String magic, byte[] constant11to17, int mask0, int mask3, Ss21Exception.Reason wrongMagic) {
            this.magic = magic.getBytes(StandardCharsets.US_ASCII);
            this.constant11to17 = constant11to17;
            this.mask0 = mask0;
            this.mask3 = mask3;
            this.wrongMagic = wrongMagic;
        }

        public String magic() {
            return new String(magic, StandardCharsets.US_ASCII);
        }

        /** Whether {@code raw} carries this layout's magic at bytes 2..5. */
        public boolean matches(byte[] raw) {
            return raw != null && raw.length >= MAGIC_OFFSET + 4 && Arrays.equals(raw, MAGIC_OFFSET, MAGIC_OFFSET + 4, magic, 0, 4);
        }
    }

    private Ss21Decoder() {}

    /** Decodes a base64 ZK 10.0 template, as ZKBio CVSecurity and the ZK enrollment helper hand it out. */
    public static Ss21Template decodeBase64(String base64) {
        return decodeBase64(base64, Layout.SS21);
    }

    public static Ss21Template decodeBase64(String base64, Layout layout) {
        return decode(Base64Bytes.decode(base64), layout);
    }

    /** Decodes a ZK 10.0 template. */
    public static Ss21Template decode(byte[] raw) {
        return decode(raw, Layout.SS21);
    }

    public static Ss21Template decode(byte[] raw, Layout layout) {
        if (raw == null || raw.length == 0) throw new Ss21Exception(Ss21Exception.Reason.EMPTY, "template is empty");
        if (raw.length < RECORDS_OFFSET) {
            throw new Ss21Exception(Ss21Exception.Reason.TOO_SHORT, "template has " + raw.length + " bytes, fewer than the " + RECORDS_OFFSET + "-byte header");
        }
        if (!layout.matches(raw)) {
            throw new Ss21Exception(layout.wrongMagic, "no " + layout.magic() + " magic at bytes 2..5; not a template in the " + layout.magic() + " layout");
        }
        if (!Arrays.equals(raw, 11, 18, layout.constant11to17, 0, 7)) {
            throw new Ss21Exception(Ss21Exception.Reason.UNSUPPORTED_VARIANT, layout.magic() + " template with unknown header constants at bytes 11..17; this variant is not supported");
        }
        byte[] d = unmask(raw, layout);
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

    /** {@link #unmask(byte[], Layout)} for ZK 10.0. */
    public static byte[] unmask(byte[] raw) {
        return unmask(raw, Layout.SS21);
    }

    /**
     * Removes the position-keyed XOR mask from byte 18 on. The key depends on the absolute
     * position mod 10: residue 1 uses the template's own length low byte (byte 9), residue 8
     * uses 0xC4, residue 0 uses 0x04 (10.0) or 0x05 (12.0), residue 3 uses 0x01 in 12.0, and all
     * other residues are unmasked. The mask is its own inverse, so this also applies it.
     */
    public static byte[] unmask(byte[] raw, Layout layout) {
        byte[] d = raw.clone();
        int lengthLow = raw.length > 9 ? raw[9] & 0xFF : 0;
        for (int i = MASK_START; i < d.length; i++) {
            d[i] = (byte) (d[i] ^ maskByte(i, lengthLow, layout));
        }
        return d;
    }

    /** The ZK 10.0 mask byte at absolute position {@code i} (only meaningful for i >= 18). */
    public static int maskByte(int i, int lengthLow) {
        return maskByte(i, lengthLow, Layout.SS21);
    }

    public static int maskByte(int i, int lengthLow, Layout layout) {
        return switch (i % 10) {
            case 0 -> layout.mask0;
            case 1 -> lengthLow;
            case 3 -> layout.mask3;
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
