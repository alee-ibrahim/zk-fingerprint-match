package io.github.aleeibrahim.zkmatch.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.aleeibrahim.zkmatch.Synthetic;
import io.github.aleeibrahim.zkmatch.gallery.GallerySource.StoredTemplate;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Minutia;
import io.github.aleeibrahim.zkmatch.zk.ZkTemplates;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GalleryTest {
    final Random rnd = new Random(7);

    @Test
    void keepsOneVersionPerFingerInPreferenceOrder() throws Exception {
        List<Ss21Minutia> a5 = Synthetic.finger(rnd), a6 = Synthetic.finger(rnd), b5 = Synthetic.finger(rnd), c2 = Synthetic.finger(rnd);
        List<StoredTemplate> stored = new ArrayList<>(List.of(
                // Person a, finger 5: all three versions; finger 6: a 10.0 that is not SS21, plus 12.0 and 13.0.
                new StoredTemplate("a", 5, "13", Synthetic.encode13Base64(a5, rnd)),
                new StoredTemplate("a", 5, "12.0", Synthetic.encode12Base64(a5, rnd)),
                new StoredTemplate("a", 5, "10", Synthetic.encodeBase64(a5, rnd)),
                new StoredTemplate("a", 6, "10", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                new StoredTemplate("a", 6, "13", Synthetic.encode13Base64(a6, rnd)),
                new StoredTemplate("a", 6, "12", Synthetic.encode12Base64(a6, rnd)),
                // Person b: only 13.0. Person c: two 13.0 templates of finger 2 (both kept). A face template.
                new StoredTemplate("b", 5, "13", Synthetic.encode13Base64(b5, rnd)),
                new StoredTemplate("c", 2, "13", Synthetic.encode13Base64(c2, rnd)),
                new StoredTemplate("c", 2, "13", Synthetic.encode13Base64(Synthetic.press(c2, rnd), rnd)),
                new StoredTemplate("c", 0, "39.3", "AAAA")));

        Gallery g = Gallery.load(source(stored));
        assertEquals(Map.of("a/5", "10", "a/6", "12", "b/5", "13", "c/2", "13"), versionsByFinger(g));
        assertEquals(5, g.size());
        assertEquals(3, g.persons());
        assertEquals(new Gallery.VersionCount(1, 1), g.versions().get("10"));
        assertEquals(new Gallery.VersionCount(2, 1), g.versions().get("12"));
        assertEquals(new Gallery.VersionCount(5, 3), g.versions().get("13"));
        assertEquals(Map.of("10:NOT_SS21", 1), g.decodeFailures());
        assertEquals(Map.of("39.3", 1), g.skippedVersions());

        // The order of arrival does not matter.
        java.util.Collections.reverse(stored);
        assertEquals(versionsByFinger(g), versionsByFinger(Gallery.load(source(stored))));

        // A different preference, and versions left out of it.
        Gallery only13 = Gallery.load(source(stored), ZkTemplates.parsePreference("13"));
        assertEquals(Map.of("a/5", "13", "a/6", "13", "b/5", "13", "c/2", "13"), versionsByFinger(only13));
        assertEquals(Map.of("10", 2, "12", 2, "39.3", 1), only13.skippedVersions());
        Gallery prefer12 = Gallery.load(source(stored), ZkTemplates.parsePreference("12,10"));
        assertEquals(Map.of("a/5", "12", "a/6", "12"), versionsByFinger(prefer12));
    }

    private static Map<String, String> versionsByFinger(Gallery g) {
        return g.entries().stream().collect(Collectors.toMap(e -> e.id() + "/" + e.finger(), Gallery.Entry::version, (x, y) -> {
            if (!x.equals(y)) throw new AssertionError("two versions kept for one finger: " + x + " and " + y);
            return x;
        }));
    }

    private static GallerySource source(List<StoredTemplate> stored) {
        return new GallerySource() {
            public String name() { return "test"; }
            public void load(Consumer<StoredTemplate> sink) { stored.forEach(sink); }
        };
    }
}
