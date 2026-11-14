package io.github.aleeibrahim.zkmatch.zk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.machinezoo.sourceafis.FingerprintMatcher;
import io.github.aleeibrahim.zkmatch.Synthetic;
import io.github.aleeibrahim.zkmatch.afis.AfisTemplates;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Decoder;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Exception;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Minutia;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Template;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Zk13DecoderTest {

    /** A 13.0 template built byte by byte, independent of the test encoder. */
    @Test
    void decodesAHandBuiltTemplate() {
        byte[] d = new byte[1552];
        System.arraycopy(new byte[]{0x6A, (byte) 0x95, 0x01, 0x10, 0x10, 0x06}, 0, d, 0, 6);
        d[10] = 0x0D;
        d[12] = 0x01;
        // Record 0 at 20: x13 = 100, y13 = 200, angle step 18 (90 degrees).
        put(d, 20, 100, 200, 18);
        // Record 1 at 44: x13 = -8 (just outside the image), y13 = 40, step 0.
        put(d, 44, -8, 40, 0);
        // Record 2 is all zero: the list ends.
        byte[] raw = d.clone();
        for (int i = 16; i < raw.length; i++) raw[i] ^= (byte) (i - 16);

        Ss21Template t = Zk13Decoder.decode(raw);
        // Transposed and scaled by 1.25: (250, 125) and (50, -10), then shifted so y starts at 0.
        assertEquals(List.of(new Ss21Minutia(250, 135, 64, 0), new Ss21Minutia(50, 0, 0, 0)), t.minutiae());
        assertEquals("zk13", t.variant());
        assertEquals(251, t.width());
        assertEquals(136, t.height());
    }

    private static void put(byte[] d, int o, int x, int y, int step) {
        d[o] = (byte) x;
        d[o + 1] = (byte) (x >> 8);
        d[o + 2] = (byte) y;
        d[o + 3] = (byte) (y >> 8);
        d[o + 4] = (byte) step;
        d[o + 9] = 0x11; // a descriptor byte, ignored
    }

    @Test
    void maskIsThePositionAndItsOwnInverse() {
        byte[] zero = new byte[600];
        byte[] masked = Zk13Decoder.unmask(zero);
        for (int i = 0; i < 16; i++) assertEquals(0, masked[i]);
        for (int i = 16; i < 600; i++) assertEquals((byte) (i - 16), masked[i], "position " + i);
        Random rnd = new Random(1);
        byte[] raw = new byte[1552];
        rnd.nextBytes(raw);
        assertArrayEquals(raw, Zk13Decoder.unmask(Zk13Decoder.unmask(raw)));
    }

    @Test
    void roundTripsSyntheticTemplatesUpToTranslation() {
        Random rnd = new Random(2);
        for (int i = 0; i < 100; i++) {
            List<Synthetic.Zk13Record> records = new ArrayList<>();
            int n = 10 + rnd.nextInt(39);
            for (int k = 0; k < n; k++) records.add(new Synthetic.Zk13Record(rnd.nextInt(300) - 20, 1 + rnd.nextInt(230), rnd.nextInt(72)));
            Ss21Template t = Zk13Decoder.decode(Synthetic.encode13(records, rnd));
            assertEquals(n, t.minutiae().size());
            int minX = records.stream().mapToInt(r -> (int) Math.round(1.25 * r.y13())).min().orElseThrow();
            int minY = records.stream().mapToInt(r -> (int) Math.round(1.25 * r.x13())).min().orElseThrow();
            for (int k = 0; k < n; k++) {
                Synthetic.Zk13Record r = records.get(k);
                Ss21Minutia m = t.minutiae().get(k);
                assertEquals(Math.round(1.25 * r.y13()) - Math.min(0, minX), m.x());
                assertEquals(Math.round(1.25 * r.x13()) - Math.min(0, minY), m.y());
                assertEquals(Math.floorMod((int) Math.round(r.step() * 256.0 / 72), 256), m.angle());
            }
        }
    }

    @Test
    void readsAtMost48Records() {
        Random rnd = new Random(3);
        List<Synthetic.Zk13Record> records = new ArrayList<>();
        for (int k = 0; k < 48; k++) records.add(new Synthetic.Zk13Record(10 + 4 * k, 20 + 3 * k, k));
        assertEquals(48, Zk13Decoder.decode(Synthetic.encode13(records, rnd)).minutiae().size());
    }

    /** The same made-up finger written as 10.0 and as 13.0 matches itself, and not another finger. */
    @Test
    void zk13MatchesThe10TemplateOfTheSameFinger() {
        Random rnd = new Random(4);
        List<Ss21Minutia> finger = Synthetic.finger(rnd), other = Synthetic.finger(rnd);
        var v10 = AfisTemplates.fromSs21(Ss21Decoder.decodeBase64(Synthetic.encodeBase64(Synthetic.press(finger, rnd), rnd)));
        var v13 = AfisTemplates.fromSs21(ZkTemplates.decodeAny(Synthetic.encode13Base64(Synthetic.press(finger, rnd), rnd)).template());
        var v13other = AfisTemplates.fromSs21(ZkTemplates.decodeAny(Synthetic.encode13Base64(other, rnd)).template());
        FingerprintMatcher m = new FingerprintMatcher(v10);
        double same = m.match(v13), different = m.match(v13other);
        assertTrue(same > 65, "same finger scored " + same);
        assertTrue(different < 30, "different finger scored " + different);
    }

    @Test
    void refusesWhatItCannotRead() {
        Random rnd = new Random(5);
        byte[] good = Synthetic.encode13(Synthetic.toZk13(Synthetic.finger(rnd)), rnd);
        assertReason(Ss21Exception.Reason.EMPTY, () -> Zk13Decoder.decode(new byte[0]));
        assertReason(Ss21Exception.Reason.TOO_SHORT, () -> Zk13Decoder.decode(new byte[30]));
        byte[] otherMagic = good.clone();
        otherMagic[1] = 0;
        assertReason(Ss21Exception.Reason.NOT_ZK13, () -> Zk13Decoder.decode(otherMagic));
        byte[] otherConstant = good.clone();
        otherConstant[10] = 0x0E;
        assertReason(Ss21Exception.Reason.UNSUPPORTED_VARIANT, () -> Zk13Decoder.decode(otherConstant));
        assertReason(Ss21Exception.Reason.NO_MINUTIAE, () -> Zk13Decoder.decode(Synthetic.encode13(List.of(), rnd)));
    }

    private static void assertReason(Ss21Exception.Reason reason, org.junit.jupiter.api.function.Executable run) {
        assertEquals(reason, assertThrows(Ss21Exception.class, run).reason());
    }
}
