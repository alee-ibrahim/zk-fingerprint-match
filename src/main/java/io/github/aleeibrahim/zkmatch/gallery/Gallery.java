package io.github.aleeibrahim.zkmatch.gallery;

import com.machinezoo.sourceafis.FingerprintTemplate;
import io.github.aleeibrahim.zkmatch.afis.AfisTemplates;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Decoder;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Exception;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One immutable snapshot of the enrolled templates, already converted to SourceAFIS
 * templates. Raw templates are decoded as they arrive and never kept.
 */
public final class Gallery {
    /** An enrolled finger. */
    public record Entry(String id, int finger, FingerprintTemplate template) {}

    private final List<Entry> entries;
    private final Instant loadedAt;
    private final Map<String, Integer> decodeFailures;
    private final Map<String, Integer> skippedVersions;
    private final Map<String, Integer> variants;
    private final long persons;

    private Gallery(List<Entry> entries, Instant loadedAt, Map<String, Integer> decodeFailures,
                    Map<String, Integer> skippedVersions, Map<String, Integer> variants) {
        this.entries = List.copyOf(entries);
        this.loadedAt = loadedAt;
        this.decodeFailures = Collections.unmodifiableMap(decodeFailures);
        this.skippedVersions = Collections.unmodifiableMap(skippedVersions);
        this.variants = Collections.unmodifiableMap(variants);
        this.persons = this.entries.stream().map(Entry::id).distinct().count();
    }

    public static Gallery empty() {
        return new Gallery(List.of(), null, new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
    }

    /** Reads a whole source into a new snapshot. Templates that are not ZK 10.0 are counted and skipped. */
    public static Gallery load(GallerySource source) throws Exception {
        Builder b = new Builder();
        source.load(b::add);
        return b.build();
    }

    public List<Entry> entries() { return entries; }
    public Instant loadedAt() { return loadedAt; }
    public Map<String, Integer> decodeFailures() { return decodeFailures; }
    public Map<String, Integer> skippedVersions() { return skippedVersions; }
    public Map<String, Integer> variants() { return variants; }
    public int size() { return entries.size(); }
    public long persons() { return persons; }

    /** Collects templates from a source; safe to feed from several threads. */
    public static final class Builder {
        private final List<Entry> entries = new ArrayList<>();
        private final Map<String, Integer> failures = new TreeMap<>();
        private final Map<String, Integer> skipped = new TreeMap<>();
        private final Map<String, Integer> variants = new TreeMap<>();

        public void add(GallerySource.StoredTemplate t) {
            String version = t.version().replaceAll("\\.0+$", "");
            if (!version.equals("10")) {
                count(skipped, version.isEmpty() ? "unknown" : version);
                return;
            }
            Entry entry;
            String variant;
            try {
                var decoded = Ss21Decoder.decodeBase64(t.template());
                variant = decoded.variant();
                entry = new Entry(t.id(), t.finger(), AfisTemplates.fromSs21(decoded));
            } catch (Ss21Exception e) {
                count(failures, e.reason().name());
                return;
            } catch (IllegalArgumentException e) {
                count(failures, "AFIS_REJECTED");
                return;
            }
            synchronized (this) {
                entries.add(entry);
                variants.merge(variant, 1, Integer::sum);
            }
        }

        private synchronized void count(Map<String, Integer> map, String key) {
            map.merge(key, 1, Integer::sum);
        }

        public synchronized Gallery build() {
            return new Gallery(entries, Instant.now(), new TreeMap<>(failures), new TreeMap<>(skipped), new TreeMap<>(variants));
        }
    }
}
