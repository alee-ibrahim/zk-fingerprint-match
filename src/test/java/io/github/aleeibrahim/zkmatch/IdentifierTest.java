package io.github.aleeibrahim.zkmatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.machinezoo.sourceafis.FingerprintTemplate;
import io.github.aleeibrahim.zkmatch.afis.AfisTemplates;
import io.github.aleeibrahim.zkmatch.gallery.Gallery;
import io.github.aleeibrahim.zkmatch.gallery.GallerySource;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Decoder;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Minutia;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 1:N identification on made-up minutiae only. */
class IdentifierTest {
    static final double THRESHOLD = 65;
    static final int PERSONS = 200;
    static List<List<Ss21Minutia>> fingers;
    static Gallery gallery;
    static ExecutorService pool;
    static Identifier identifier;

    @BeforeAll
    static void setUp() throws Exception {
        Random rnd = new Random(42);
        fingers = new ArrayList<>();
        for (int i = 0; i < PERSONS; i++) fingers.add(Synthetic.finger(rnd));
        GallerySource source = new GallerySource() {
            public String name() { return "synthetic"; }
            public void load(java.util.function.Consumer<StoredTemplate> sink) {
                for (int i = 0; i < PERSONS; i++) sink.accept(new StoredTemplate("p" + i, 5, "10", Synthetic.encodeBase64(fingers.get(i), rnd)));
                sink.accept(new StoredTemplate("other", 1, "12.0", "AAAA"));
                sink.accept(new StoredTemplate("other", 2, "13", "AAAA"));
                sink.accept(new StoredTemplate("face", 0, "39.3", "AAAA"));
                sink.accept(new StoredTemplate("broken", 3, "10", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIj"));
            }
        };
        gallery = Gallery.load(source);
        pool = Executors.newFixedThreadPool(4);
        identifier = new Identifier(pool, 4);
    }

    @AfterAll
    static void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void countsWhatItSkips() {
        assertEquals(PERSONS, gallery.size());
        assertEquals(PERSONS, gallery.persons());
        assertEquals(Map.of("39.3", 1), gallery.skippedVersions());
        assertEquals(Map.of("10:NOT_SS21", 1, "12:TOO_SHORT", 1, "13:TOO_SHORT", 1), gallery.decodeFailures());
        assertEquals(Map.of("280x361", PERSONS), gallery.variants());
    }

    @Test
    void findsEnrolledFingersFromAnotherPress() throws Exception {
        Random rnd = new Random(7);
        for (int k = 0; k < 20; k++) {
            int who = rnd.nextInt(PERSONS);
            FingerprintTemplate probe = probe(Synthetic.press(fingers.get(who), rnd), rnd);
            Identifier.Result r = identifier.identify(gallery, probe, 5, THRESHOLD);
            assertEquals("p" + who, r.candidates().get(0).id(), "press " + k);
            assertEquals(5, r.candidates().get(0).finger());
            assertTrue(r.match(), "score " + r.candidates().get(0).score());
            assertTrue(r.margin() > 20, "margin " + r.margin());
            assertEquals(5, r.candidates().size());
            assertEquals(PERSONS, r.gallerySize());
        }
    }

    @Test
    void doesNotMatchFingersThatAreNotEnrolled() throws Exception {
        Random rnd = new Random(8);
        double worst = 0;
        for (int k = 0; k < 20; k++) {
            FingerprintTemplate probe = probe(Synthetic.finger(rnd), rnd);
            Identifier.Result r = identifier.identify(gallery, probe, 3, THRESHOLD);
            assertFalse(r.match(), "score " + r.candidates().get(0).score());
            worst = Math.max(worst, r.candidates().get(0).score());
        }
        System.out.printf("synthetic non-match: highest score %.1f (threshold %.0f)%n", worst, THRESHOLD);
    }

    @Test
    void candidatesAreTheBestFingerPerPersonInOrder() throws Exception {
        Random rnd = new Random(9);
        List<Ss21Minutia> f = fingers.get(3);
        GallerySource twoFingers = new GallerySource() {
            public String name() { return "synthetic"; }
            public void load(java.util.function.Consumer<StoredTemplate> sink) {
                sink.accept(new StoredTemplate("a", 1, "10", Synthetic.encodeBase64(Synthetic.finger(rnd), rnd)));
                sink.accept(new StoredTemplate("a", 2, "10", Synthetic.encodeBase64(f, rnd)));
                sink.accept(new StoredTemplate("b", 1, "10", Synthetic.encodeBase64(Synthetic.finger(rnd), rnd)));
            }
        };
        Gallery g = Gallery.load(twoFingers);
        Identifier.Result r = identifier.identify(g, probe(Synthetic.press(f, rnd), rnd), 5, THRESHOLD);
        assertEquals(2, r.candidates().size());
        assertEquals("a", r.candidates().get(0).id());
        assertEquals(2, r.candidates().get(0).finger());
        assertEquals(Identifier.round(r.candidates().get(0).score() - r.candidates().get(1).score()), r.margin());
    }

    @Test
    void emptyGalleryMatchesNoOne() throws Exception {
        Identifier.Result r = identifier.identify(Gallery.empty(), probe(fingers.get(0), new Random(1)), 5, THRESHOLD);
        assertTrue(r.candidates().isEmpty());
        assertFalse(r.match());
    }

    static FingerprintTemplate probe(List<Ss21Minutia> minutiae, Random rnd) {
        return AfisTemplates.fromSs21(Ss21Decoder.decodeBase64(Synthetic.encodeBase64(minutiae, rnd)));
    }
}
