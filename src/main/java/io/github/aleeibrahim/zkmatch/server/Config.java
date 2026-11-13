package io.github.aleeibrahim.zkmatch.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Settings from environment variables. Secrets can also come from files (NAME_FILE), the
 * usual way to mount them in containers. See the README for the full list.
 */
public record Config(
        int port,
        String matcherToken,
        String gallerySource,
        String cvsBaseUrl,
        String cvsToken,
        String cvsPublicKeyPin,
        boolean cvsTlsInsecure,
        int cvsConcurrency,
        Duration cvsTimeout,
        String galleryDir,
        Duration refreshInterval,
        double threshold,
        double imageDpi,
        int maxBodyBytes,
        int matchWorkers,
        int httpThreads) {

    public static Config fromEnv(Map<String, String> env) {
        String token = secret(env, "MATCHER_TOKEN");
        if (token == null || token.length() < 16) {
            throw new IllegalArgumentException("MATCHER_TOKEN (or MATCHER_TOKEN_FILE) must be set to a random string of at least 16 characters");
        }
        String source = env.getOrDefault("GALLERY_SOURCE", "cvsecurity").trim().toLowerCase();
        int cpus = Runtime.getRuntime().availableProcessors();
        return new Config(
                integer(env, "PORT", 8080),
                token,
                source,
                env.getOrDefault("CVS_BASE_URL", ""),
                secret(env, "CVS_TOKEN"),
                env.getOrDefault("CVS_PUBLIC_KEY_PIN", ""),
                Boolean.parseBoolean(env.getOrDefault("CVS_TLS_INSECURE", "false")),
                integer(env, "CVS_CONCURRENCY", 4),
                Duration.ofSeconds(integer(env, "CVS_TIMEOUT_SECONDS", 20)),
                env.getOrDefault("GALLERY_DIR", ""),
                Duration.ofMinutes(integer(env, "REFRESH_MINUTES", 15)),
                decimal(env, "MATCH_THRESHOLD", 65),
                decimal(env, "IMAGE_DPI", 500),
                integer(env, "MAX_BODY_BYTES", 4 * 1024 * 1024),
                integer(env, "MATCH_WORKERS", Math.max(1, cpus)),
                integer(env, "HTTP_THREADS", 8));
    }

    /** NAME, or the contents of the file NAME_FILE points to. */
    static String secret(Map<String, String> env, String name) {
        String file = env.get(name + "_FILE");
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file)).trim();
            } catch (IOException e) {
                throw new IllegalArgumentException(name + "_FILE cannot be read");
            }
        }
        String v = env.get(name);
        return v == null ? null : v.trim();
    }

    private static int integer(Map<String, String> env, String name, int fallback) {
        String v = env.get(name);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
    }

    private static double decimal(Map<String, String> env, String name, double fallback) {
        String v = env.get(name);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    /** For logs: everything except the secrets. */
    @Override
    public String toString() {
        return "Config[port=" + port + ", gallerySource=" + gallerySource + ", cvsBaseUrl=" + (cvsBaseUrl.isBlank() ? "-" : "set")
                + ", cvsPublicKeyPin=" + (cvsPublicKeyPin.isBlank() ? "-" : "set") + ", cvsTlsInsecure=" + cvsTlsInsecure
                + ", refreshMinutes=" + refreshInterval.toMinutes() + ", threshold=" + threshold + ", imageDpi=" + imageDpi
                + ", maxBodyBytes=" + maxBodyBytes + ", matchWorkers=" + matchWorkers + "]";
    }
}
