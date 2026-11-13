package io.github.aleeibrahim.zkmatch.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.aleeibrahim.zkmatch.Synthetic;
import io.github.aleeibrahim.zkmatch.gallery.CvSecuritySource;
import io.github.aleeibrahim.zkmatch.gallery.GalleryService;
import io.github.aleeibrahim.zkmatch.ss21.Ss21Minutia;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The HTTP API end to end, with a fake CVSecurity API (plain HTTP, synthetic templates) as the
 * gallery source.
 */
class MatchServerTest {
    static final String TOKEN = "test-token-0123456789";
    static final String CVS_TOKEN = "cvs-token-abc";
    static final ObjectMapper JSON = new ObjectMapper();

    HttpServer cvs;
    List<String> seenQueries = new CopyOnWriteArrayList<>();
    Map<String, List<Ss21Minutia>> fingers = new HashMap<>();
    GalleryService galleries;
    MatchServer server;
    HttpClient http = HttpClient.newHttpClient();
    Random rnd = new Random(5);
    List<Synthetic.Vortex> pattern;

    @BeforeEach
    void setUp() throws Exception {
        // 250 people so the person list needs two pages of 200; every third has no fingerprints.
        List<String> pins = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            pins.add(String.valueOf(1000 + i));
            if (i % 3 != 0) fingers.put(String.valueOf(1000 + i), Synthetic.finger(rnd));
        }
        // One person enrolled from a synthetic image, for the image path.
        pattern = Synthetic.vortices(rnd, 22, 300, 375);
        var enrolled = io.github.aleeibrahim.zkmatch.afis.AfisTemplates.fromImage(Synthetic.image(pattern, 300, 375, 0, 0), 500);
        pins.add("9999");
        fingers.put("9999", Synthetic.minutiaeOf(enrolled));
        cvs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cvs.createContext("/api/v2/person/getPersonList", ex -> {
            seenQueries.add(ex.getRequestURI().getRawQuery());
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            int page = Integer.parseInt(q.get("pageNo")), size = Integer.parseInt(q.get("pageSize"));
            StringBuilder rows = new StringBuilder();
            for (int i = (page - 1) * size; i < Math.min(pins.size(), page * size); i++) {
                if (rows.length() > 0) rows.append(',');
                rows.append("{\"pin\":\"").append(pins.get(i)).append("\",\"name\":\"Made Up\",\"personPhoto\":\"").append("A".repeat(2000))
                        .append("\",\"personPwd\":\"x\",\"cardNo\":\"123\"}");
            }
            reply(ex, "{\"code\":0,\"message\":\"success\",\"data\":{\"total\":" + pins.size() + ",\"data\":[" + rows + "]}}");
        });
        cvs.createContext("/api/bioTemplate/getFgListByPin/", ex -> {
            seenQueries.add(ex.getRequestURI().getRawQuery());
            String pin = ex.getRequestURI().getPath().substring("/api/bioTemplate/getFgListByPin/".length());
            List<Ss21Minutia> f = fingers.get(pin);
            String data = f == null ? "[]" : "[{\"pin\":\"" + pin + "\",\"templateNo\":5,\"version\":\"10\",\"template\":\"" + Synthetic.encodeBase64(f, rnd)
                    + "\"},{\"pin\":\"" + pin + "\",\"templateNo\":5,\"version\":\"12\",\"template\":\"AAAA\"}]";
            reply(ex, "{\"code\":0,\"message\":\"success\",\"data\":" + data + "}");
        });
        cvs.start();

        Map<String, String> env = Map.of(
                "PORT", "0",
                "MATCHER_TOKEN", TOKEN,
                "CVS_BASE_URL", "http://127.0.0.1:" + cvs.getAddress().getPort(),
                "CVS_TOKEN", CVS_TOKEN,
                "MAX_BODY_BYTES", "200000",
                "MATCH_WORKERS", "2");
        Config config = Config.fromEnv(env);
        galleries = new GalleryService(Main.source(config), Duration.ofMinutes(15));
        server = new MatchServer(config, galleries);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
        galleries.close();
        cvs.stop(0);
    }

    @Test
    void healthReportsLoadingThenTheGallery() throws Exception {
        JsonNode before = json(get("/health"));
        assertEquals("loading", before.path("status").asText());
        assertEquals(0, before.path("templates").asInt());

        galleries.refresh();
        HttpResponse<String> r = get("/health");
        assertEquals(200, r.statusCode());
        JsonNode h = json(r);
        assertEquals("ok", h.path("status").asText());
        assertEquals("cvsecurity", h.path("source").asText());
        assertEquals(fingers.size(), h.path("templates").asInt());
        assertEquals(fingers.size(), h.path("persons").asInt());
        assertEquals(fingers.size(), h.path("skippedVersions").path("12").asInt());
        assertEquals(65.0, h.path("threshold").asDouble());
        assertTrue(seenQueries.stream().allMatch(q -> q.contains("access_token=" + CVS_TOKEN)));
        assertTrue(seenQueries.stream().anyMatch(q -> q.contains("pageNo=2")));
    }

    @Test
    void identifyNeedsTheToken() throws Exception {
        assertEquals(401, post("/identify", "{}", null).statusCode());
        assertEquals(401, post("/identify", "{}", "wrong-token-0123456789").statusCode());
        assertEquals(401, post("/reload", "", null).statusCode());
    }

    @Test
    void identifyAnswers503UntilTheGalleryIsLoaded() throws Exception {
        String body = "{\"template\":\"" + Synthetic.encodeBase64(Synthetic.finger(rnd), rnd) + "\"}";
        assertEquals(503, post("/identify", body, TOKEN).statusCode());
    }

    @Test
    void identifiesAPressSentAsATemplate() throws Exception {
        galleries.refresh();
        String who = fingers.keySet().stream().sorted().skip(17).findFirst().orElseThrow();
        String body = "{\"template\":\"" + Synthetic.encodeBase64(Synthetic.press(fingers.get(who), rnd), rnd) + "\",\"top\":3}";
        HttpResponse<String> r = post("/identify", body, TOKEN);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        JsonNode j = json(r);
        assertEquals(who, j.path("candidates").get(0).path("id").asText());
        assertEquals(5, j.path("candidates").get(0).path("finger").asInt());
        assertEquals(3, j.path("candidates").size());
        assertTrue(j.path("match").asBoolean());
        assertTrue(j.path("margin").asDouble() > 0);
        assertEquals(fingers.size(), j.path("gallerySize").asInt());

        // A threshold in the request wins over the configured one.
        JsonNode strict = json(post("/identify", body.replace("\"top\":3", "\"threshold\":100000"), TOKEN));
        assertFalse(strict.path("match").asBoolean());
        assertEquals(100000.0, strict.path("threshold").asDouble());
    }

    @Test
    void refusesBadInput() throws Exception {
        galleries.refresh();
        assertEquals(400, post("/identify", "not json", TOKEN).statusCode());
        assertEquals(400, post("/identify", "{}", TOKEN).statusCode());
        assertEquals(400, post("/identify", "{\"image\":\"a\",\"template\":\"b\"}", TOKEN).statusCode());
        assertEquals(400, post("/identify", "{\"image\":\"%%%\"}", TOKEN).statusCode());
        HttpResponse<String> notImage = post("/identify", "{\"image\":\"" + Base64.getEncoder().encodeToString("hello".getBytes()) + "\"}", TOKEN);
        assertEquals(400, notImage.statusCode());
        HttpResponse<String> notSs21 = post("/identify", "{\"template\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}", TOKEN);
        assertEquals(400, notSs21.statusCode());
        assertTrue(json(notSs21).path("error").asText().contains("SS21"));
        // A plain white image has no fingerprint in it.
        java.awt.image.BufferedImage white = new java.awt.image.BufferedImage(300, 375, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        white.getGraphics().fillRect(0, 0, 300, 375);
        javax.imageio.ImageIO.write(white, "png", out);
        HttpResponse<String> noFinger = post("/identify", "{\"image\":\"data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray()) + "\"}", TOKEN);
        assertEquals(400, noFinger.statusCode(), noFinger.body());
        assertEquals(413, post("/identify", "{\"image\":\"" + "A".repeat(300_000) + "\"}", TOKEN).statusCode());
        assertEquals(405, get("/identify").statusCode());
        assertEquals(404, get("/nothing").statusCode());
    }

    @Test
    void identifiesASyntheticImage() throws Exception {
        galleries.refresh();
        // A second "press" of the image-enrolled person, shifted a little, as a data URL.
        String png = Base64.getEncoder().encodeToString(Synthetic.image(pattern, 300, 375, 7, 4));
        JsonNode j = json(post("/identify", "{\"image\":\"data:image/png;base64," + png + "\"}", TOKEN));
        assertEquals("9999", j.path("candidates").get(0).path("id").asText(), j.toString());
        assertTrue(j.path("match").asBoolean(), j.toString());
    }

    @Test
    void reloadStartsInTheBackground() throws Exception {
        HttpResponse<String> r = post("/reload", "", TOKEN);
        assertEquals(202, r.statusCode());
        for (int i = 0; i < 100 && galleries.gallery().loadedAt() == null; i++) Thread.sleep(50);
        assertEquals(fingers.size(), galleries.gallery().size());
    }

    @Test
    void personListParserKeepsOnlyPins() throws Exception {
        String body = "{\"code\":0,\"data\":{\"data\":[{\"pin\":\"1\",\"personPhoto\":\"AAAA\",\"nested\":{\"pin\":\"x\"}},{\"pin\":2,\"name\":\"n\"}]}}";
        List<String> pins = new ArrayList<>();
        try (var p = new com.fasterxml.jackson.core.JsonFactory().createParser(body)) {
            assertEquals("0", CvSecurityParser.readPins(p, pins));
        }
        assertEquals(List.of("1", "2"), pins);
    }

    /** Lets this test reach the package-private parser. */
    static final class CvSecurityParser {
        static String readPins(com.fasterxml.jackson.core.JsonParser p, List<String> pins) throws Exception {
            var m = CvSecuritySource.class.getDeclaredMethod("readPins", com.fasterxml.jackson.core.JsonParser.class, List.class);
            m.setAccessible(true);
            return (String) m.invoke(null, p, pins);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            out.put(kv.substring(0, i), java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
