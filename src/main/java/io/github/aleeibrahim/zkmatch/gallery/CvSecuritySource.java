package io.github.aleeibrahim.zkmatch.gallery;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Reads fingerprint templates from a ZKBio CVSecurity server through its REST API.
 *
 * <p>Only two calls are used, both read-only: the person list (only each person's pin is
 * read; photos, passwords and every other field are skipped by the streaming parser without
 * being kept), and each person's fingerprint templates. The API token travels as the
 * {@code access_token} query parameter, as the API expects, so request URLs are never logged
 * or put into error messages.
 */
public final class CvSecuritySource implements GallerySource {
    public static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 500;

    private final String baseUrl;
    private final String token;
    private final int concurrency;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final JsonFactory jsonFactory = new JsonFactory();

    /**
     * @param baseUrl       e.g. https://cvsecurity.example:8443 (no trailing /api)
     * @param token         the API token
     * @param publicKeyPin  optional "sha256//BASE64" pin of the server's public key, for a
     *                      self-signed certificate; when set, only a server holding that key is
     *                      trusted and the host name is not checked
     * @param insecureTls   trust any certificate (testing only; ignored when a pin is set)
     */
    public CvSecuritySource(String baseUrl, String token, String publicKeyPin, boolean insecureTls, int concurrency, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("CVSecurity base URL is not set");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("CVSecurity API token is not set");
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.concurrency = Math.max(1, concurrency);
        this.timeout = timeout;
        this.http = buildClient(publicKeyPin, insecureTls, timeout);
    }

    @Override
    public String name() {
        return "cvsecurity";
    }

    @Override
    public void load(Consumer<StoredTemplate> sink) throws Exception {
        List<String> pins = listPins();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "cvsecurity-load");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> jobs = new ArrayList<>(pins.size());
            for (String pin : pins) jobs.add(pool.submit(() -> {
                templatesOf(pin, sink);
                return null;
            }));
            for (Future<?> job : jobs) job.get();
        } finally {
            pool.shutdownNow();
        }
    }

    /** Every person's pin, page by page. Nothing else from the person list is kept. */
    List<String> listPins() throws IOException, InterruptedException {
        List<String> pins = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String path = "/api/v2/person/getPersonList";
            HttpRequest request = request(path, "pageNo=" + page + "&pageSize=" + PAGE_SIZE)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<InputStream> response = send(request, path);
            int before = pins.size();
            try (InputStream body = response.body(); JsonParser p = jsonFactory.createParser(body)) {
                String code = readPins(p, pins);
                if (!"0".equals(code)) throw new IOException("CVSecurity answered code " + code + " on " + path);
            }
            if (pins.size() - before < PAGE_SIZE) break;
        }
        return pins;
    }

    /**
     * Walks the person list answer, collecting the "pin" of every object that sits directly in
     * an array (the person rows) and the top-level "code". Large fields (photos) are passed over
     * as tokens and never turned into strings.
     */
    static String readPins(JsonParser p, List<String> pins) throws IOException {
        String code = null;
        // true for an array, false for an object, innermost last.
        java.util.ArrayDeque<Boolean> stack = new java.util.ArrayDeque<>();
        JsonToken t;
        while ((t = p.nextToken()) != null) {
            switch (t) {
                case START_OBJECT -> stack.addLast(false);
                case START_ARRAY -> stack.addLast(true);
                case END_OBJECT, END_ARRAY -> stack.pollLast();
                case FIELD_NAME -> {
                    String field = p.currentName();
                    JsonToken v = p.nextToken();
                    if (v == JsonToken.START_OBJECT) {
                        stack.addLast(false);
                    } else if (v == JsonToken.START_ARRAY) {
                        stack.addLast(true);
                    } else if ("pin".equals(field) && inArrayRow(stack)) {
                        String pin = p.getValueAsString();
                        if (pin != null && !pin.isBlank()) pins.add(pin.trim());
                    } else if ("code".equals(field) && stack.size() == 1) {
                        code = p.getValueAsString();
                    }
                }
                default -> { }
            }
        }
        return code;
    }

    private static boolean inArrayRow(java.util.ArrayDeque<Boolean> stack) {
        if (stack.size() < 2) return false;
        var it = stack.descendingIterator();
        boolean self = it.next();
        return !self && it.next();
    }

    private void templatesOf(String pin, Consumer<StoredTemplate> sink) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(pin, StandardCharsets.UTF_8);
        String path = "/api/bioTemplate/getFgListByPin/";
        HttpRequest request = request(path + encoded, "pin=" + encoded).GET().build();
        HttpResponse<InputStream> response = send(request, path + "{pin}");
        JsonNode body;
        try (InputStream in = response.body()) {
            body = json.readTree(in);
        }
        String code = body.path("code").asText();
        if (!"0".equals(code)) {
            // A person without fingerprints is not an error for the gallery.
            if (body.path("data").isMissingNode() || body.path("data").isNull()) return;
            throw new IOException("CVSecurity answered code " + code + " on " + path + "{pin}");
        }
        JsonNode data = body.path("data");
        if (data.isObject() && data.has("data")) data = data.path("data");
        for (JsonNode t : data) {
            String template = t.path("template").asText("");
            if (template.isEmpty()) continue;
            sink.accept(new StoredTemplate(pin, t.path("templateNo").asInt(-1), t.path("version").asText(""), template));
        }
    }

    private HttpRequest.Builder request(String path, String query) {
        String url = baseUrl + path + "?" + query + "&access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Accept", "application/json");
    }

    /** Sends a request; failures name the API path only, never the URL (it carries the token). */
    private HttpResponse<InputStream> send(HttpRequest request, String pathForErrors) throws IOException, InterruptedException {
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IOException("CVSecurity request failed on " + pathForErrors + ": " + e.getClass().getSimpleName());
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("CVSecurity answered HTTP " + response.statusCode() + " on " + pathForErrors);
        }
        return response;
    }

    private static HttpClient buildClient(String publicKeyPin, boolean insecureTls, Duration timeout) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER);
        try {
            if (publicKeyPin != null && !publicKeyPin.isBlank()) {
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, new TrustManager[]{new PinnedTrustManager(publicKeyPin)}, null);
                // The pin replaces the CA and name checks: a self-signed server reached by IP
                // has neither, and only a server holding the pinned key gets past the handshake.
                System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
                SSLParameters params = ssl.getDefaultSSLParameters();
                params.setEndpointIdentificationAlgorithm(null);
                b.sslContext(ssl).sslParameters(params);
            } else if (insecureTls) {
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, new TrustManager[]{new TrustAll()}, null);
                System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
                b.sslContext(ssl);
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not set up TLS: " + e.getMessage(), e);
        }
        return b.build();
    }

    /** Trusts exactly one public key, given as curl's "sha256//BASE64" of its SubjectPublicKeyInfo. */
    static final class PinnedTrustManager implements X509TrustManager {
        private final byte[] pin;

        PinnedTrustManager(String pin) {
            String v = pin.trim();
            if (v.startsWith("sha256//")) v = v.substring(8);
            this.pin = Base64.getDecoder().decode(v);
            if (this.pin.length != 32) throw new IllegalArgumentException("public key pin must be a SHA-256 hash (sha256//BASE64)");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("no server certificate");
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(chain[0].getPublicKey().getEncoded());
                if (!MessageDigest.isEqual(hash, pin)) throw new CertificateException("server public key does not match the pin");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new CertificateException(e);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static final class TrustAll implements X509TrustManager {
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
