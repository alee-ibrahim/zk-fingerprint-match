package io.github.aleeibrahim.zkmatch.gallery;

import com.machinezoo.sourceafis.FingerprintTemplate;
import io.github.aleeibrahim.zkmatch.afis.AfisTemplates;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Exception;
import io.github.aleeibrahim.zkmatch.zk.ZkTemplates;
import io.github.aleeibrahim.zkmatch.zk.ZkTemplates.Format;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One immutable snapshot of the enrolled templates, already converted to SourceAFIS
 * templates. Raw templates are decoded as they arrive and never kept.
 *
 * <p>A person's finger is often stored in several versions (10.0, 12.0 and 13.0 from the same
 * enrollment). The gallery keeps one version per finger: the first one in the preference order
 * that decodes. Templates without a finger number are all kept.
 */
public final class Gallery {
    /** The default preference: 10.0 and 12.0 carry the same minutiae; 13.0 matches live images less well. */
    public static final List<Format> DEFAULT_PREFERENCE = List.of(Format.V10, Format.V12, Format.V13);

    /** An enrolled finger. {@code version} is the template version it was decoded from, e.g. "12". */
    public record Entry(String id, int finger, String version, FingerprintTemplate template) {}

    /** Per template version: how many decoded, and how many are in the gallery after choosing one version per finger. */
    public record VersionCount(int decoded, int used) {}

    private final List<Entry> entries;
    private final Instant loadedAt;
    private final Map<String, Integer> decodeFailures;
    private final Map<String, Integer> skippedVersions;
    private final Map<String, Integer> variants;
    private final Map<String, VersionCount> versions;
    private final long persons;

    private Gallery(List<Entry> entries, Instant loadedAt, Map<String, Integer> decodeFailures,
                    Map<String, Integer> skippedVersions, Map<String, Integer> variants, Map<String, VersionCount> versions) {
        this.entries = List.copyOf(entries);
        this.loadedAt = loadedAt;
        this.decodeFailures = Collections.unmodifiableMap(decodeFailures);
        this.skippedVersions = Collections.unmodifiableMap(skippedVersions);
        this.variants = Collections.unmodifiableMap(variants);
        this.versions = Collections.unmodifiableMap(versions);
        this.persons = this.entries.stream().map(Entry::id).distinct().count();
    }

    public static Gallery empty() {
        return new Gallery(List.of(), null, new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
    }

    /** Reads a whole source into a new snapshot, with the default version preference. */
    public static Gallery load(GallerySource source) throws Exception {
        return load(source, DEFAULT_PREFERENCE);
    }

    /**
     * Reads a whole source into a new snapshot. Versions not in {@code preference} are counted
     * and skipped.
     */
    public static Gallery load(GallerySource source, List<Format> preference) throws Exception {
        Builder b = new Builder(preference);
        source.load(b::add);
        return b.build();
    }

    public List<Entry> entries() { return entries; }
    public Instant loadedAt() { return loadedAt; }
    /** Keyed "version:REASON", e.g. "10:NOT_SS21". */
    public Map<String, Integer> decodeFailures() { return decodeFailures; }
    public Map<String, Integer> skippedVersions() { return skippedVersions; }
    public Map<String, Integer> variants() { return variants; }
    public Map<String, VersionCount> versions() { return versions; }
    public int size() { return entries.size(); }
    public long persons() { return persons; }

    /** Collects templates from a source; safe to feed from several threads. */
    public static final class Builder {
        private final List<Format> preference;
        /** The best version so far per person and finger. */
        private final Map<String, Slot> slots = new HashMap<>();
        private final List<Entry> withoutFinger = new ArrayList<>();
        private final Map<String, Integer> failures = new TreeMap<>();
        private final Map<String, Integer> skipped = new TreeMap<>();
        private final Map<String, Integer> variants = new TreeMap<>();
        private final Map<String, Integer> decoded = new TreeMap<>();

        private record Slot(int rank, List<Entry> entries) {}

        public Builder() {
            this(DEFAULT_PREFERENCE);
        }

        public Builder(List<Format> preference) {
            if (preference.isEmpty()) throw new IllegalArgumentException("no template versions to load");
            this.preference = List.copyOf(preference);
        }

        public void add(GallerySource.StoredTemplate t) {
            String version = ZkTemplates.normalize(t.version());
            Format format = Format.ofVersion(version);
            if (format == null || !preference.contains(format)) {
                count(skipped, version.isEmpty() ? "unknown" : version);
                return;
            }
            Entry entry;
            String variant;
            try {
                var decoded = ZkTemplates.decodeStored(format, t.template()).template();
                variant = decoded.variant();
                entry = new Entry(t.id(), t.finger(), format.version(), AfisTemplates.fromSs21(decoded));
            } catch (Ss21Exception e) {
                count(failures, version + ":" + e.reason().name());
                return;
            } catch (IllegalArgumentException e) {
                count(failures, version + ":AFIS_REJECTED");
                return;
            }
            int rank = preference.indexOf(format);
            synchronized (this) {
                decoded.merge(format.version(), 1, Integer::sum);
                variants.merge(variant, 1, Integer::sum);
                if (t.finger() < 0) {
                    withoutFinger.add(entry);
                    return;
                }
                slots.compute(t.id() + "\u0000" + t.finger(), (k, s) -> {
                    if (s == null || rank < s.rank()) return new Slot(rank, new ArrayList<>(List.of(entry)));
                    if (rank == s.rank()) s.entries().add(entry);
                    return s;
                });
            }
        }

        private synchronized void count(Map<String, Integer> map, String key) {
            map.merge(key, 1, Integer::sum);
        }

        public synchronized Gallery build() {
            List<Entry> entries = new ArrayList<>(withoutFinger);
            slots.values().forEach(s -> entries.addAll(s.entries()));
            entries.sort(Comparator.comparing(Entry::id).thenComparingInt(Entry::finger).thenComparing(Entry::version));
            Map<String, VersionCount> versions = new TreeMap<>();
            for (Format f : preference) {
                int used = (int) entries.stream().filter(e -> e.version().equals(f.version())).count();
                versions.put(f.version(), new VersionCount(decoded.getOrDefault(f.version(), 0), used));
            }
            return new Gallery(entries, Instant.now(), new TreeMap<>(failures), new TreeMap<>(skipped), new TreeMap<>(variants), versions);
        }
    }
}
