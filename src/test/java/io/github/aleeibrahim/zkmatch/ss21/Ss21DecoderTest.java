package io.github.aleeibrahim.zkmatch.ss21;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aleeibrahim.zkmatch.Synthetic;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Ss21DecoderTest {

    @Test
    void roundTripsSyntheticTemplates() {
        Random rnd = new Random(1);
        for (int i = 0; i < 200; i++) {
            List<Ss21Minutia> minutiae = Synthetic.finger(rnd);
            int width = 260 + rnd.nextInt(60), height = 340 + rnd.nextInt(40);
            byte[] raw = Synthetic.encode(width, height, minutiae, rnd.nextInt(600), rnd);
            Ss21Template t = Ss21Decoder.decodeBase64(Base64.getEncoder().encodeToString(raw));
            assertEquals(width, t.width());
            assertEquals(height, t.height());
            assertEquals(width + "x" + height, t.variant());
            assertEquals(minutiae, t.minutiae());
        }
    }

    @Test
    void maskKeysByPositionModTen() {
        int lengthLow = 0x5A;
        for (int i = 18; i < 120; i++) {
            int expected = switch (i % 10) {
                case 0 -> 0x04;
                case 1 -> lengthLow;
                case 8 -> 0xC4;
                default -> 0;
            };
            assertEquals(expected, Ss21Decoder.maskByte(i, lengthLow), "position " + i);
        }
    }

    @Test
    void maskLeavesTheFirst18BytesAloneAndIsItsOwnInverse() {
        Random rnd = new Random(2);
        byte[] raw = new byte[300];
        rnd.nextBytes(raw);
        byte[] once = Ss21Decoder.unmask(raw);
        for (int i = 0; i < 18; i++) assertEquals(raw[i], once[i]);
        assertArrayEquals(raw, Ss21Decoder.unmask(once));
    }

    /** A template built byte by byte, independent of the test encoder. */
    @Test
    void decodesAHandBuiltTemplate() {
        int length = 36; // header + one record
        byte[] raw = new byte[length];
        System.arraycopy("SS21".getBytes(StandardCharsets.US_ASCII), 0, raw, 2, 4);
        raw[8] = 0;
        raw[9] = (byte) length;
        byte[] constant = {0x04, 0x08, 0x05, 0x07, 0x09, (byte) 0xCE, (byte) 0xD0};
        System.arraycopy(constant, 0, raw, 11, 7);
        // Width 280 = 0x0118 at 20..21: byte 20 is residue 0 (mask 0x04), 21 is residue 1 (mask = byte 9).
        raw[20] = (byte) (0x18 ^ 0x04);
        raw[21] = (byte) (0x01 ^ length);
        // Height 361 = 0x0169 at 22..23 (no mask).
        raw[22] = 0x69;
        raw[23] = 0x01;
        raw[29] = 1;
        // Record at 30: x = 0x0123 (30 is residue 0, 31 residue 1), y = 0x00F0, angle 64, flags 1.
        raw[30] = (byte) (0x23 ^ 0x04);
        raw[31] = (byte) (0x01 ^ length);
        raw[32] = (byte) 0xF0;
        raw[33] = 0x00;
        raw[34] = 64;
        raw[35] = 1;

        Ss21Template t = Ss21Decoder.decode(raw);
        assertEquals(280, t.width());
        assertEquals(361, t.height());
        assertEquals(List.of(new Ss21Minutia(0x0123, 0xF0, 64, 1)), t.minutiae());
        assertEquals(Math.PI / 2, t.minutiae().get(0).radians(), 1e-9);
    }

    @Test
    void refusesWhatItCannotRead() {
        Random rnd = new Random(3);
        byte[] good = Synthetic.encode(280, 361, Synthetic.finger(rnd), 100, rnd);

        assertReason(Ss21Exception.Reason.EMPTY, () -> Ss21Decoder.decodeBase64(" "));
        assertReason(Ss21Exception.Reason.NOT_BASE64, () -> Ss21Decoder.decodeBase64("***not base64***"));
        assertReason(Ss21Exception.Reason.TOO_SHORT, () -> Ss21Decoder.decode(new byte[20]));

        byte[] otherMagic = good.clone();
        otherMagic[2] = 'X';
        assertReason(Ss21Exception.Reason.NOT_SS21, () -> Ss21Decoder.decode(otherMagic));

        byte[] otherConstants = good.clone();
        otherConstants[13] ^= 0x55;
        assertReason(Ss21Exception.Reason.UNSUPPORTED_VARIANT, () -> Ss21Decoder.decode(otherConstants));

        byte[] zero = Synthetic.encode(280, 361, List.of(), 10, rnd);
        assertReason(Ss21Exception.Reason.NO_MINUTIAE, () -> Ss21Decoder.decode(zero));

        byte[] truncated = java.util.Arrays.copyOf(good, 40);
        assertReason(Ss21Exception.Reason.TRUNCATED, () -> Ss21Decoder.decode(truncated));

        byte[] hugeWidth = Synthetic.encode(5000, 361, Synthetic.finger(rnd), 10, rnd);
        assertReason(Ss21Exception.Reason.UNSUPPORTED_VARIANT, () -> Ss21Decoder.decode(hugeWidth));
    }

    @Test
    void roundTripsSyntheticSr23Templates() {
        Random rnd = new Random(4);
        for (int i = 0; i < 200; i++) {
            List<Ss21Minutia> minutiae = Synthetic.finger(rnd);
            int width = 260 + rnd.nextInt(60), height = 340 + rnd.nextInt(40);
            byte[] raw = Synthetic.encode(Ss21Decoder.Layout.SR23, width, height, minutiae, rnd.nextInt(600), rnd);
            Ss21Template t = Ss21Decoder.decode(raw, Ss21Decoder.Layout.SR23);
            assertEquals(width, t.width());
            assertEquals(height, t.height());
            assertEquals(minutiae, t.minutiae());
        }
    }

    @Test
    void sr23MaskAddsResidues0And3() {
        int lengthLow = 0x5A;
        for (int i = 18; i < 120; i++) {
            int expected = switch (i % 10) {
                case 0 -> 0x05;
                case 1 -> lengthLow;
                case 3 -> 0x01;
                case 8 -> 0xC4;
                default -> 0;
            };
            assertEquals(expected, Ss21Decoder.maskByte(i, lengthLow, Ss21Decoder.Layout.SR23), "position " + i);
        }
    }

    /** A 12.0 template built byte by byte, independent of the test encoder. */
    @Test
    void decodesAHandBuiltSr23Template() {
        int length = 36;
        byte[] raw = new byte[length];
        System.arraycopy("SR23".getBytes(StandardCharsets.US_ASCII), 0, raw, 2, 4);
        raw[9] = (byte) length;
        byte[] constant = {0x05, 0x05, 0x05, 0x07, 0x09, (byte) 0xCE, (byte) 0xD0};
        System.arraycopy(constant, 0, raw, 11, 7);
        raw[20] = (byte) (0x18 ^ 0x05);      // width 280: residue 0 (mask 0x05)
        raw[21] = (byte) (0x01 ^ length);    // residue 1 (mask = byte 9)
        raw[22] = 0x69;                      // height 361
        raw[23] = (byte) (0x01 ^ 0x01);      // residue 3 (mask 0x01)
        raw[29] = 1;
        raw[30] = (byte) (0x23 ^ 0x05);      // x = 0x0123
        raw[31] = (byte) (0x01 ^ length);
        raw[32] = (byte) 0xF0;               // y = 0x00F0
        raw[33] = (byte) (0x00 ^ 0x01);
        raw[34] = 64;
        raw[35] = 100;

        Ss21Template t = Ss21Decoder.decode(raw, Ss21Decoder.Layout.SR23);
        assertEquals(280, t.width());
        assertEquals(361, t.height());
        assertEquals(List.of(new Ss21Minutia(0x0123, 0xF0, 64, 100)), t.minutiae());
        // The same bytes are not a 10.0 template.
        assertReason(Ss21Exception.Reason.NOT_SS21, () -> Ss21Decoder.decode(raw));
        assertReason(Ss21Exception.Reason.NOT_SR23, () -> Ss21Decoder.decode(Synthetic.encode(280, 361, Synthetic.finger(new Random(9)), 10, new Random(9)), Ss21Decoder.Layout.SR23));
    }

    private static void assertReason(Ss21Exception.Reason reason, org.junit.jupiter.api.function.Executable run) {
        assertEquals(reason, assertThrows(Ss21Exception.class, run).reason());
    }
}
