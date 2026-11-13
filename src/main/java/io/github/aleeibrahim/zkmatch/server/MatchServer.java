package io.github.aleeibrahim.zkmatch.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.machinezoo.sourceafis.FingerprintTemplate;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.aleeibrahim.zkmatch.Identifier;
import io.github.aleeibrahim.zkmatch.afis.AfisTemplates;
import io.github.aleeibrahim.zkmatch.gallery.Gallery;
import io.github.aleeibrahim.zkmatch.gallery.GalleryService;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Decoder;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Exception;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * The HTTP API: POST /identify, POST /reload (both need the bearer token) and GET /health.
 *
 * <p>Images and templates sent to /identify live only for the request: they are turned into a
 * SourceAFIS template, matched and dropped. They are never logged or written anywhere, and
 * the log line for a request carries only the path, status and time taken.
 */
public final class MatchServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(MatchServer.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    /** Fewer minutiae than this in a live image is not a usable fingerprint. */
    static final int MIN_PROBE_MINUTIAE = 5;
    static final int MAX_TOP = 20;

    private final Config config;
    private final GalleryService galleries;
    private final Identifier identifier;
    private final byte[] token;
    private final HttpServer server;
    private final ExecutorService httpPool;
    private final ExecutorService matchPool;

    public MatchServer(Config config, GalleryService galleries) throws IOException {
        this.config = config;
        this.galleries = galleries;
        this.token = config.matcherToken().getBytes(StandardCharsets.UTF_8);
        this.matchPool = Executors.newFixedThreadPool(config.matchWorkers(), daemon("match"));
        this.identifier = new Identifier(matchPool, config.matchWorkers());
        this.httpPool = Executors.newFixedThreadPool(config.httpThreads(), daemon("http"));
        this.server = HttpServer.create(new InetSocketAddress(config.port()), 64);
        server.setExecutor(httpPool);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        httpPool.shutdownNow();
        matchPool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        long started = System.nanoTime();
        int status = 500;
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            status = switch (path) {
                case "/health" -> method.equals("GET") ? health(ex) : methodNotAllowed(ex, "GET");
                case "/identify" -> method.equals("POST") ? (authorized(ex) ? identify(ex) : unauthorized(ex)) : methodNotAllowed(ex, "POST");
                case "/reload" -> method.equals("POST") ? (authorized(ex) ? reload(ex) : unauthorized(ex)) : methodNotAllowed(ex, "POST");
                default -> error(ex, 404, "not found");
            };
        } catch (Exception e) {
            LOG.warning("request failed: " + e.getClass().getSimpleName());
            try {
                status = error(ex, 500, "internal error");
            } catch (IOException ignored) {
                // The client went away.
            }
        } finally {
            ex.close();
            long ms = (System.nanoTime() - started) / 1_000_000;
            int s = status;
            LOG.fine(() -> ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + s + " " + ms + "ms");
        }
    }

    private int health(HttpExchange ex) throws IOException {
        Gallery g = galleries.gallery();
        boolean loaded = g.loadedAt() != null;
        ObjectNode o = JSON.createObjectNode();
        o.put("status", loaded ? "ok" : galleries.lastError() != null ? "error" : "loading");
        o.put("source", galleries.sourceName());
        o.put("templates", g.size());
        o.put("persons", g.persons());
        o.put("lastRefresh", loaded ? g.loadedAt().toString() : null);
        o.put("lastAttempt", galleries.lastAttempt() == null ? null : galleries.lastAttempt().toString());
        o.put("lastLoadMs", galleries.lastLoadMillis());
        o.put("lastError", galleries.lastError());
        o.put("loading", galleries.loading());
        o.set("decodeFailures", JSON.valueToTree(g.decodeFailures()));
        o.set("skippedVersions", JSON.valueToTree(g.skippedVersions()));
        o.set("headerVariants", JSON.valueToTree(g.variants()));
        o.put("threshold", config.threshold());
        o.put("refreshMinutes", config.refreshInterval().toMinutes());
        // A load that never succeeded is not ready to identify anyone.
        return send(ex, loaded || galleries.lastError() == null ? 200 : 503, o);
    }

    private int reload(HttpExchange ex) throws IOException {
        drain(ex);
        boolean started = galleries.reloadAsync();
        ObjectNode o = JSON.createObjectNode();
        o.put("status", started ? "reloading" : "already reloading");
        return send(ex, 202, o);
    }

    private int identify(HttpExchange ex) throws Exception {
        byte[] body = readLimited(ex.getRequestBody(), config.maxBodyBytes());
        if (body == null) return error(ex, 413, "request body is larger than " + config.maxBodyBytes() + " bytes");

        JsonNode req;
        try {
            req = JSON.readTree(body);
        } catch (IOException e) {
            return error(ex, 400, "body is not JSON");
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
        if (req == null || !req.isObject()) return error(ex, 400, "body must be a JSON object");

        double threshold = req.path("threshold").isNumber() ? req.path("threshold").asDouble() : config.threshold();
        int top = req.path("top").isInt() ? Math.min(MAX_TOP, Math.max(1, req.path("top").asInt())) : 5;
        double dpi = req.path("dpi").isNumber() ? req.path("dpi").asDouble() : config.imageDpi();
        if (dpi < 200 || dpi > 1500) return error(ex, 400, "dpi must be between 200 and 1500");

        FingerprintTemplate probe;
        String image = req.path("image").asText("");
        String template = req.path("template").asText("");
        if (!image.isEmpty() == !template.isEmpty()) return error(ex, 400, "send exactly one of image or template");
        if (!image.isEmpty()) {
            byte[] bytes;
            try {
                bytes = Base64.getMimeDecoder().decode(stripDataUrl(image));
            } catch (IllegalArgumentException e) {
                return error(ex, 400, "image is not valid base64");
            }
            try {
                probe = AfisTemplates.fromImage(bytes, dpi);
            } catch (IllegalArgumentException e) {
                return error(ex, 400, e.getMessage());
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
            if (minutiaeOf(probe) < MIN_PROBE_MINUTIAE) return error(ex, 400, "no usable fingerprint found in the image");
        } else {
            try {
                probe = AfisTemplates.fromSs21(Ss21Decoder.decodeBase64(template));
            } catch (Ss21Exception e) {
                return error(ex, 400, "template: " + e.getMessage());
            }
        }
        req = null;
        image = null;
        template = null;

        Gallery g = galleries.gallery();
        if (g.loadedAt() == null) return error(ex, 503, "gallery not loaded yet");

        long started = System.nanoTime();
        Identifier.Result r = identifier.identify(g, probe, top, threshold);
        long ms = (System.nanoTime() - started) / 1_000_000;

        ObjectNode o = JSON.createObjectNode();
        var list = o.putArray("candidates");
        for (Identifier.Candidate c : r.candidates()) {
            ObjectNode n = list.addObject();
            n.put("id", c.id());
            n.put("finger", c.finger());
            n.put("score", Math.round(c.score() * 10) / 10.0);
        }
        o.put("match", r.match());
        o.put("margin", r.margin());
        o.put("threshold", r.threshold());
        o.put("gallerySize", r.gallerySize());
        o.put("elapsedMs", ms);
        return send(ex, 200, o);
    }

    /** How many minutiae SourceAFIS found, read from its own serialized template. */
    static int minutiaeOf(FingerprintTemplate t) {
        try {
            return CBOR.readTree(t.toByteArray()).path("positionsX").size();
        } catch (IOException e) {
            return 0;
        }
    }

    static String stripDataUrl(String s) {
        int comma = s.indexOf(',');
        return s.startsWith("data:") && comma > 0 ? s.substring(comma + 1) : s;
    }

    private boolean authorized(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        return MessageDigest.isEqual(h.substring(7).trim().getBytes(StandardCharsets.UTF_8), token);
    }

    private int unauthorized(HttpExchange ex) throws IOException {
        drain(ex);
        ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        return error(ex, 401, "missing or wrong bearer token");
    }

    private int methodNotAllowed(HttpExchange ex, String allow) throws IOException {
        drain(ex);
        ex.getResponseHeaders().set("Allow", allow);
        return error(ex, 405, "method not allowed");
    }

    private static int error(HttpExchange ex, int status, String message) throws IOException {
        ObjectNode o = JSON.createObjectNode();
        o.put("error", message);
        return send(ex, status, o);
    }

    private static int send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
        return status;
    }

    /** The body, or null when it is longer than {@code max}. */
    static byte[] readLimited(InputStream in, int max) throws IOException {
        byte[] bytes = in.readNBytes(max + 1);
        if (bytes.length > max) {
            java.util.Arrays.fill(bytes, (byte) 0);
            return null;
        }
        return bytes;
    }

    /** Discards an unread body. Closing the request stream reads what is left of it, up to its length. */
    private static void drain(HttpExchange ex) throws IOException {
        ex.getRequestBody().close();
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
