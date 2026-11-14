package io.github.aleeibrahim.zkmatch.gallery;

import java.time.Duration;
import io.github.aleeibrahim.zkmatch.zk.ZkTemplates;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Keeps the current gallery in memory and refreshes it on a schedule and on demand.
 * Nothing is written to disk. A failed refresh keeps the previous snapshot and records the
 * error (without template data) for /health.
 */
public final class GalleryService implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(GalleryService.class.getName());

    private final GallerySource source;
    private final Duration interval;
    private final List<ZkTemplates.Format> preference;
    private final AtomicReference<Gallery> current = new AtomicReference<>(Gallery.empty());
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gallery-refresh");
        t.setDaemon(true);
        return t;
    });
    private volatile String lastError;
    private volatile Instant lastAttempt;
    private volatile long lastLoadMillis;

    public GalleryService(GallerySource source, Duration interval) {
        this(source, interval, Gallery.DEFAULT_PREFERENCE);
    }

    /** @param preference the template versions to load, most preferred first (one version is kept per finger) */
    public GalleryService(GallerySource source, Duration interval, List<ZkTemplates.Format> preference) {
        this.source = source;
        this.interval = interval;
        this.preference = List.copyOf(preference);
    }

    /** Loads now in the background, then every interval. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::refresh, 0, Math.max(1, interval.toSeconds()), TimeUnit.SECONDS);
    }

    /** Asks for a refresh in the background. False when one is already running. */
    public boolean reloadAsync() {
        if (loading.get()) return false;
        scheduler.execute(this::refresh);
        return true;
    }

    /** Loads the source now, on this thread. Returns false when another load was running. */
    public boolean refresh() {
        if (!loading.compareAndSet(false, true)) return false;
        long started = System.nanoTime();
        lastAttempt = Instant.now();
        try {
            Gallery g = Gallery.load(source, preference);
            current.set(g);
            lastError = null;
            lastLoadMillis = (System.nanoTime() - started) / 1_000_000;
            LOG.info(() -> String.format("gallery loaded from %s: %d templates, %d persons, versions %s, decode failures %s, skipped versions %s, %d ms",
                    source.name(), g.size(), g.persons(), g.versions(), g.decodeFailures(), g.skippedVersions(), lastLoadMillis));
            return true;
        } catch (Exception e) {
            // Messages from the source name paths and codes only, never URLs, tokens or templates.
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.warning("gallery refresh failed, keeping the previous one: " + lastError);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return true;
        } finally {
            loading.set(false);
        }
    }

    public Gallery gallery() { return current.get(); }
    public boolean loading() { return loading.get(); }
    public String lastError() { return lastError; }
    public Instant lastAttempt() { return lastAttempt; }
    public long lastLoadMillis() { return lastLoadMillis; }
    public Duration interval() { return interval; }
    public String sourceName() { return source.name(); }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
