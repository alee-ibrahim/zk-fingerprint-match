package io.github.aleeibrahim.zkmatch;

import com.machinezoo.sourceafis.FingerprintTemplate;
import io.github.aleeibrahim.zkmatch.afis.AfisTemplates;
import io.github.aleeibrahim.zkmatch.gallery.Gallery;
import io.github.aleeibrahim.zkmatch.gallery.GallerySource;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Decoder;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Minutia;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Identify latency against a synthetic gallery of 600 templates, the size of a few hundred
 * people with two fingers each. Run with {@code mvn test -Pbench}.
 */
@Tag("benchmark")
class IdentifyBenchmarkTest {
    static final int GALLERY = 600, RUNS = 60, WARMUP = 15;

    @Test
    void identifyLatency() throws Exception {
        Random rnd = new Random(2026);
        List<List<Ss21Minutia>> fingers = new ArrayList<>();
        for (int i = 0; i < GALLERY; i++) fingers.add(Synthetic.finger(rnd));
        List<String> encoded = new ArrayList<>();
        for (List<Ss21Minutia> f : fingers) encoded.add(Synthetic.encodeBase64(f, rnd));

        long t0 = System.nanoTime();
        Gallery gallery = Gallery.load(new GallerySource() {
            public String name() { return "synthetic"; }
            public void load(java.util.function.Consumer<StoredTemplate> sink) {
                for (int i = 0; i < GALLERY; i++) sink.accept(new StoredTemplate("p" + (i / 2), 5 + i % 2, "10", encoded.get(i)));
            }
        });
        double loadMs = (System.nanoTime() - t0) / 1e6;

        List<Synthetic.Vortex> pattern = Synthetic.vortices(rnd, 22, 300, 375);
        byte[] image = Synthetic.image(pattern, 300, 375, 6, -4);

        int cpus = Runtime.getRuntime().availableProcessors();
        System.out.printf("%nbenchmark: %d templates (%d persons), decode+convert %.0f ms (%.2f ms each), %d CPUs%n",
                gallery.size(), gallery.persons(), loadMs, loadMs / GALLERY, cpus);
        for (int workers : new int[]{1, cpus}) {
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            try {
                Identifier id = new Identifier(pool, workers);
                double[] tpl = new double[RUNS], img = new double[RUNS], extract = new double[RUNS];
                for (int r = -WARMUP; r < RUNS; r++) {
                    int who = rnd.nextInt(GALLERY);
                    FingerprintTemplate probe = AfisTemplates.fromSs21(Ss21Decoder.decodeBase64(Synthetic.encodeBase64(Synthetic.press(fingers.get(who), rnd), rnd)));
                    long a = System.nanoTime();
                    Identifier.Result res = id.identify(gallery, probe, 5, 65);
                    long b = System.nanoTime();
                    FingerprintTemplate live = AfisTemplates.fromImage(image, 500);
                    long c = System.nanoTime();
                    id.identify(gallery, live, 5, 65);
                    long d = System.nanoTime();
                    if (!res.candidates().get(0).id().equals("p" + (who / 2))) throw new AssertionError("wrong rank 1 in benchmark");
                    if (r >= 0) {
                        tpl[r] = (b - a) / 1e6;
                        extract[r] = (c - b) / 1e6;
                        img[r] = (d - b) / 1e6;
                    }
                }
                System.out.printf("workers=%d  template probe: %s | image probe (extract + search): %s | extraction alone: %s%n",
                        workers, stats(tpl), stats(img), stats(extract));
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
