package io.github.aleeibrahim.zkmatch;

import com.machinezoo.sourceafis.FingerprintMatcher;
import com.machinezoo.sourceafis.FingerprintTemplate;
import io.github.aleeibrahim.zkmatch.gallery.Gallery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 1:N identification against a gallery snapshot.
 *
 * <p>The probe is indexed once into a {@link FingerprintMatcher} per worker thread and then
 * matched against every enrolled template; the enrolled side was converted when the gallery
 * was loaded, so a search does no template work for it.
 */
public final class Identifier {
    /** The best finger of one person. */
    public record Candidate(String id, int finger, double score) {}

    /**
     * @param candidates the best score per person, highest first, at most {@code top}
     * @param match      whether the best score reaches the threshold
     * @param margin     best score minus the second best (the best score when there is only one)
     */
    public record Result(List<Candidate> candidates, boolean match, double margin, double threshold, int gallerySize) {}

    private final ExecutorService pool;
    private final int workers;

    /** @param pool runs the slices of a search; {@code workers} slices per search */
    public Identifier(ExecutorService pool, int workers) {
        this.pool = pool;
        this.workers = Math.max(1, workers);
    }

    public Result identify(Gallery gallery, FingerprintTemplate probe, int top, double threshold) throws Exception {
        List<Gallery.Entry> entries = gallery.entries();
        Map<String, Candidate> best = new HashMap<>();
        if (!entries.isEmpty()) {
            int slices = Math.min(workers, Math.max(1, entries.size() / 64));
            int step = (entries.size() + slices - 1) / slices;
            List<Future<Map<String, Candidate>>> parts = new ArrayList<>(slices);
            for (int s = 0; s < slices; s++) {
                int from = s * step, to = Math.min(entries.size(), from + step);
                if (from >= to) break;
                List<Gallery.Entry> slice = entries.subList(from, to);
                parts.add(pool.submit(() -> {
                    // One matcher per slice: a matcher indexes the probe once and is reused for every candidate.
                    FingerprintMatcher matcher = new FingerprintMatcher(probe);
                    Map<String, Candidate> local = new HashMap<>();
                    for (Gallery.Entry e : slice) keepBest(local, new Candidate(e.id(), e.finger(), matcher.match(e.template())));
                    return local;
                }));
            }
            for (Future<Map<String, Candidate>> part : parts) part.get().values().forEach(c -> keepBest(best, c));
        }
        List<Candidate> ranked = new ArrayList<>(best.values());
        ranked.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::id));
        double first = ranked.isEmpty() ? 0 : ranked.get(0).score();
        double second = ranked.size() > 1 ? ranked.get(1).score() : 0;
        List<Candidate> shown = ranked.subList(0, Math.min(Math.max(1, top), ranked.size()));
        return new Result(List.copyOf(shown), !ranked.isEmpty() && first >= threshold, round(first - second), threshold, entries.size());
    }

    private static void keepBest(Map<String, Candidate> map, Candidate c) {
        map.merge(c.id(), c, (a, b) -> b.score() > a.score() ? b : a);
    }

    static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
