package io.github.aleeibrahim.zkmatch.server;

import io.github.aleeibrahim.zkmatch.gallery.CvSecuritySource;
import io.github.aleeibrahim.zkmatch.gallery.DirectorySource;
import io.github.aleeibrahim.zkmatch.gallery.GallerySource;
import io.github.aleeibrahim.zkmatch.gallery.GalleryService;
import java.nio.file.Path;
import java.util.logging.Logger;

/** Starts the service from environment variables (see README). */
public final class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tFT%1$tT%1$tz %4$s %3$s: %5$s%6$s%n");
        Config config;
        GallerySource source;
        try {
            config = Config.fromEnv(System.getenv());
            source = source(config);
        } catch (IllegalArgumentException e) {
            System.err.println("configuration error: " + e.getMessage());
            System.exit(2);
            return;
        }
        LOG.info("starting with " + config);
        GalleryService galleries = new GalleryService(source, config.refreshInterval());
        MatchServer server = new MatchServer(config, galleries);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            galleries.close();
        }));
        server.start();
        galleries.start();
        LOG.info("listening on :" + server.port());
    }

    static GallerySource source(Config c) {
        return switch (c.gallerySource()) {
            case "cvsecurity" -> new CvSecuritySource(c.cvsBaseUrl(), c.cvsToken(), c.cvsPublicKeyPin(), c.cvsTlsInsecure(), c.cvsConcurrency(), c.cvsTimeout());
            case "directory" -> {
                if (c.galleryDir().isBlank()) throw new IllegalArgumentException("GALLERY_DIR must be set for GALLERY_SOURCE=directory");
                yield new DirectorySource(Path.of(c.galleryDir()));
            }
            default -> throw new IllegalArgumentException("GALLERY_SOURCE must be cvsecurity or directory");
        };
    }
}
