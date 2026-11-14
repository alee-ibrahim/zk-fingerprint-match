package io.github.aleeibrahim.zkmatch;

import com.machinezoo.sourceafis.FingerprintTemplate;
import io.github.aleeibrahim.zkmatch.afis.AfisTemplates;
import io.github.aleeibrahim.zkmatch.gallery.Gallery;
import io.github.aleeibrahim.zkmatch.gallery.GallerySource;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Decoder;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Minutia;
import io.github.aleeibrahim.zkmatch.zk.ZkTemplates;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Identify latency against a synthetic gallery of 600 fingers, the size of a few hundred
 * people with two fingers each. Every finger is stored as ZK 10.0, 12.0 and 13.0, as a USB
 * enrollment stores it, except every tenth, which has only 13.0; the gallery keeps one version
 * per finger. Run with {@code mvn test -Pbench}.
 */
@Tag("benchmark")
class IdentifyBenchmarkTest {
    static final int GALLERY = 600, RUNS = 60, WARMUP = 15;

    @Test
    void identifyLatency() throws Exception {
        Random rnd = new Random(2026);
        List<List<Ss21Minutia>> fingers = new ArrayList<>();
        for (int i = 0; i < GALLERY; i++) fingers.add(Synthetic.finger(rnd));
        List<GallerySource.StoredTemplate> stored = new ArrayList<>();
        for (int i = 0; i < GALLERY; i++) {
            String id = "p" + (i / 2);
            int finger = 5 + i % 2;
            List<Ss21Minutia> f = fingers.get(i);
            if (i % 10 != 9) {
                stored.add(new GallerySource.StoredTemplate(id, finger, "10", Synthetic.encodeBase64(f, rnd)));
                stored.add(new GallerySource.StoredTemplate(id, finger, "12", Synthetic.encode12Base64(f, rnd)));
            }
            stored.add(new GallerySource.StoredTemplate(id, finger, "13", Synthetic.encode13Base64(f, rnd)));
        }

        long t0 = System.nanoTime();
        Gallery gallery = Gallery.load(new GallerySource() {
            public String name() { return "synthetic"; }
            public void load(java.util.function.Consumer<StoredTemplate> sink) {
                stored.forEach(sink);
            }
        });
        double loadMs = (System.nanoTime() - t0) / 1e6;

        List<Synthetic.Vortex> pattern = Synthetic.vortices(rnd, 22, 300, 375);
        byte[] image = Synthetic.image(pattern, 300, 375, 6, -4);

        int cpus = Runtime.getRuntime().availableProcessors();
        System.out.printf("%nbenchmark: %d stored templates -> %d in the gallery (%d persons, versions %s), decode+convert %.0f ms (%.2f ms each), %d CPUs%n",
                stored.size(), gallery.size(), gallery.persons(), gallery.versions(), loadMs, loadMs / stored.size(), cpus);
        for (int workers : new int[]{1, cpus}) {
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            try {
                Identifier id = new Identifier(pool, workers);
                double[] tpl = new double[RUNS], tpl13 = new double[RUNS], img = new double[RUNS], extract = new double[RUNS];
                for (int r = -WARMUP; r < RUNS; r++) {
                    int who = rnd.nextInt(GALLERY);
                    FingerprintTemplate probe = AfisTemplates.fromSs21(Ss21Decoder.decodeBase64(Synthetic.encodeBase64(Synthetic.press(fingers.get(who), rnd), rnd)));
                    FingerprintTemplate probe13 = AfisTemplates.fromSs21(ZkTemplates.decodeAny(Synthetic.encode13Base64(Synthetic.press(fingers.get(who), rnd), rnd)).template());
                    long z = System.nanoTime();
                    Identifier.Result res13 = id.identify(gallery, probe13, 5, 65);
                    long a = System.nanoTime();
                    Identifier.Result res = id.identify(gallery, probe, 5, 65);
                    long b = System.nanoTime();
                    FingerprintTemplate live = AfisTemplates.fromImage(image, 500);
                    long c = System.nanoTime();
                    id.identify(gallery, live, 5, 65);
                    long d = System.nanoTime();
                    if (!res.candidates().get(0).id().equals("p" + (who / 2))) throw new AssertionError("wrong rank 1 in benchmark");
                    if (!res13.candidates().get(0).id().equals("p" + (who / 2))) throw new AssertionError("wrong rank 1 for a 13.0 probe in benchmark");
                    if (r >= 0) {
                        tpl[r] = (b - a) / 1e6;
                        tpl13[r] = (a - z) / 1e6;
                        extract[r] = (c - b) / 1e6;
                        img[r] = (d - b) / 1e6;
                    }
                }
                System.out.printf("workers=%d  10.0 template probe: %s | 13.0 template probe: %s | image probe (extract + search): %s | extraction alone: %s%n",
                        workers, stats(tpl), stats(tpl13), stats(img), stats(extract));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    static String stats(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return String.format("p50 %.1f ms, p95 %.1f ms, max %.1f ms", s[s.length / 2], s[(int) (s.length * 0.95)], s[s.length - 1]);
    }
}
