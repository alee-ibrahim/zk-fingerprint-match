package io.github.aleeibrahim.zkmatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.machinezoo.sourceafis.FingerprintTemplate;
import io.github.aleeibrahim.zkmatch.afis.AfisTemplates;
import io.github.aleeibrahim.zkmatch.gallery.Gallery;
import io.github.aleeibrahim.zkmatch.gallery.GallerySource;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Live image in, SS21 gallery on the other side, as at a key desk. The images are synthetic
 * ridge patterns; the gallery templates are SS21 templates written from the minutiae
 * SourceAFIS found in the first "press" of each pattern.
 */
class ImagePathTest {
    static final int W = 300, H = 375;

    @Test
    void imagesNeverGoThroughADiskCache() {
        AfisTemplates.fromImage(Synthetic.image(List.of(), W, H, 0, 0), 500);
        assertFalse(javax.imageio.ImageIO.getUseCache());
    }

    @Test
    void identifiesASecondPressOfASyntheticFinger() throws Exception {
        Random rnd = new Random(11);
        List<List<Synthetic.Vortex>> patterns = new ArrayList<>();
        for (int i = 0; i < 12; i++) patterns.add(Synthetic.vortices(rnd, 22, W, H));

        GallerySource source = new GallerySource() {
            public String name() { return "synthetic"; }
            public void load(java.util.function.Consumer<StoredTemplate> sink) {
                for (int i = 0; i < patterns.size(); i++) {
                    FingerprintTemplate enrolled = AfisTemplates.fromImage(Synthetic.image(patterns.get(i), W, H, 0, 0), 500);
                    sink.accept(new StoredTemplate("p" + i, 6, "10", Synthetic.encodeBase64(Synthetic.minutiaeOf(enrolled), rnd)));
                }
            }
        };
        Gallery gallery = Gallery.load(source);
        assertEquals(patterns.size(), gallery.size());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Identifier identifier = new Identifier(pool, 2);
            for (int who : new int[]{0, 5, 11}) {
                byte[] press = Synthetic.image(patterns.get(who), W, H, 9, -6);
                FingerprintTemplate probe = AfisTemplates.fromImage(press, 500);
                Identifier.Result r = identifier.identify(gallery, probe, 3, 65);
                assertEquals("p" + who, r.candidates().get(0).id());
                assertTrue(r.match(), "score " + r.candidates().get(0).score());
            }
            byte[] stranger = Synthetic.image(Synthetic.vortices(new Random(99), 22, W, H), W, H, 0, 0);
            Identifier.Result r = identifier.identify(gallery, AfisTemplates.fromImage(stranger, 500), 3, 65);
            assertFalse(r.match(), "score " + r.candidates().get(0).score());
        } finally {
            pool.shutdownNow();
        }
    }
}
