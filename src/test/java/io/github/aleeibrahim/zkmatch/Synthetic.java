package io.github.aleeibrahim.zkmatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.machinezoo.sourceafis.FingerprintTemplate;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Decoder;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Minutia;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Synthetic test data only: made-up minutiae, SS21 templates written from them, and
 * fingerprint-like images drawn from a ridge phase field. No real fingerprint is used anywhere
 * in this repository.
 */
public final class Synthetic {
    private Synthetic() {}

    public static final int WIDTH = 280, HEIGHT = 361;

    /** Writes an SS21 template the way the decoder reads one (the inverse of {@link Ss21Decoder}). */
    public static byte[] encode(int width, int height, List<Ss21Minutia> minutiae, int trailing, Random rnd) {
        int n = minutiae.size();
        if (n > 255) throw new IllegalArgumentException("at most 255 minutiae");
        int length = Ss21Decoder.RECORDS_OFFSET + Ss21Decoder.RECORD_SIZE * n + trailing;
        byte[] d = new byte[length];
        d[0] = (byte) rnd.nextInt(256);
        d[1] = (byte) rnd.nextInt(256);
        System.arraycopy("SS21".getBytes(StandardCharsets.US_ASCII), 0, d, 2, 4);
        d[8] = (byte) (length >> 8);
        d[9] = (byte) length;
        d[10] = (byte) rnd.nextInt(256);
        byte[] constant = {0x04, 0x08, 0x05, 0x07, 0x09, (byte) 0xCE, (byte) 0xD0};
        System.arraycopy(constant, 0, d, 11, constant.length);
        d[18] = (byte) 0xC4;
        d[20] = (byte) width;
        d[21] = (byte) (width >> 8);
        d[22] = (byte) height;
        d[23] = (byte) (height >> 8);
        d[28] = (byte) rnd.nextInt(256);
        d[29] = (byte) n;
        for (int j = 0; j < n; j++) {
            Ss21Minutia m = minutiae.get(j);
            int o = Ss21Decoder.RECORDS_OFFSET + Ss21Decoder.RECORD_SIZE * j;
            d[o] = (byte) m.x();
            d[o + 1] = (byte) (m.x() >> 8);
            d[o + 2] = (byte) m.y();
            d[o + 3] = (byte) (m.y() >> 8);
            d[o + 4] = (byte) m.angle();
            d[o + 5] = (byte) m.flags();
        }
        for (int i = Ss21Decoder.RECORDS_OFFSET + Ss21Decoder.RECORD_SIZE * n; i < length; i++) d[i] = (byte) rnd.nextInt(256);
        // The mask is its own inverse.
        return Ss21Decoder.unmask(d);
    }

    public static String encodeBase64(List<Ss21Minutia> minutiae, Random rnd) {
        return Base64.getEncoder().encodeToString(encode(WIDTH, HEIGHT, minutiae, 200 + rnd.nextInt(400), rnd));
    }

    /** A made-up finger: 28..45 minutiae spread over the sensor, at least 14 px apart. */
    public static List<Ss21Minutia> finger(Random rnd) {
        int n = 28 + rnd.nextInt(18);
        List<Ss21Minutia> out = new ArrayList<>();
        int guard = 0;
        while (out.size() < n && guard++ < 10_000) {
            int x = 20 + rnd.nextInt(WIDTH - 40), y = 20 + rnd.nextInt(HEIGHT - 40);
            boolean far = out.stream().allMatch(m -> Math.hypot(m.x() - x, m.y() - y) >= 14);
            if (far) out.add(new Ss21Minutia(x, y, rnd.nextInt(256), rnd.nextInt(4)));
        }
        return out;
    }

    /**
     * Another press of the same made-up finger: rotated a few degrees, shifted, every point
     * jittered, about a fifth of the minutiae missing and a few spurious ones added.
     */
    public static List<Ss21Minutia> press(List<Ss21Minutia> finger, Random rnd) {
        double rot = Math.toRadians(rnd.nextDouble() * 16 - 8);
        int dx = rnd.nextInt(21) - 10, dy = rnd.nextInt(21) - 10;
        double cx = WIDTH / 2.0, cy = HEIGHT / 2.0;
        List<Ss21Minutia> out = new ArrayList<>();
        for (Ss21Minutia m : finger) {
            if (rnd.nextDouble() < 0.2) continue;
            double x0 = m.x() - cx, y0 = m.y() - cy;
            int x = (int) Math.round(cx + x0 * Math.cos(rot) - y0 * Math.sin(rot) + dx + rnd.nextInt(5) - 2);
            int y = (int) Math.round(cy + x0 * Math.sin(rot) + y0 * Math.cos(rot) + dy + rnd.nextInt(5) - 2);
            if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) continue;
            int a = Math.floorMod(m.angle() + (int) Math.round(rot * 256 / (2 * Math.PI)) + rnd.nextInt(7) - 3, 256);
            out.add(new Ss21Minutia(x, y, a, m.flags()));
        }
        for (int i = 0; i < 4; i++) out.add(new Ss21Minutia(20 + rnd.nextInt(WIDTH - 40), 20 + rnd.nextInt(HEIGHT - 40), rnd.nextInt(256), 0));
        return out;
    }

    /** Where ridges split or end in a synthetic image: a phase vortex of +1 or -1. */
    public record Vortex(double x, double y, int sign) {}

    public static List<Vortex> vortices(Random rnd, int count, int width, int height) {
        List<Vortex> out = new ArrayList<>();
        int guard = 0;
        while (out.size() < count && guard++ < 10_000) {
            double x = 40 + rnd.nextDouble() * (width - 80), y = 50 + rnd.nextDouble() * (height - 100);
            boolean far = out.stream().allMatch(v -> Math.hypot(v.x() - x, v.y() - y) >= 24);
            if (far) out.add(new Vortex(x, y, rnd.nextBoolean() ? 1 : -1));
        }
        return out;
    }

    /**
     * A fingerprint-like grayscale image: gently curved parallel ridges (period ~9 px, as at
     * 500 dpi) whose phase winds once around each vortex, which makes a ridge ending or
     * bifurcation there. {@code dx}, {@code dy} shift the pattern within the frame, like a
     * second press placed a little differently. Returns PNG bytes.
     */
    public static byte[] image(List<Vortex> vortices, int width, int height, double dx, double dy) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        double f = 2 * Math.PI / 9.0;
        double cx = width / 2.0 + dx, cy = height / 2.0 + dy;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double px = x - dx, py = y - dy;
                double ex = (x - cx) / (width * 0.45), ey = (y - cy) / (height * 0.47);
                int v;
                if (ex * ex + ey * ey > 1) {
                    v = 255;
                } else {
                    double phase = f * (py + 0.0018 * (px - width / 2.0) * (px - width / 2.0));
                    for (Vortex vx : vortices) phase += vx.sign() * Math.atan2(py - vx.y(), px - vx.x());
                    v = (int) Math.round(128 + 110 * Math.cos(phase));
                }
                img.getRaster().setSample(x, y, 0, v);
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The minutiae SourceAFIS found in a template, as SS21 records (for building a gallery from an image). */
    public static List<Ss21Minutia> minutiaeOf(FingerprintTemplate t) {
        try {
            JsonNode n = new ObjectMapper(new CBORFactory()).readTree(t.toByteArray());
            List<Ss21Minutia> out = new ArrayList<>();
            for (int i = 0; i < n.path("positionsX").size() && i < 255; i++) {
                double rad = n.path("directions").get(i).asDouble();
                int a = Math.floorMod((int) Math.round(rad * 256 / (2 * Math.PI)), 256);
                out.add(new Ss21Minutia(n.path("positionsX").get(i).asInt(), n.path("positionsY").get(i).asInt(), a, 0));
            }
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
