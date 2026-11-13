package io.github.aleeibrahim.zkmatch.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** For the container's HEALTHCHECK: exits 0 when GET /health answers 200. The image has no curl. */
public final class HealthCheck {
    private HealthCheck() {}

    public static void main(String[] args) {
        String port = System.getenv().getOrDefault("PORT", "8080");
        try {
            HttpResponse<Void> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).timeout(Duration.ofSeconds(4)).build(),
                    HttpResponse.BodyHandlers.discarding());
            System.exit(r.statusCode() == 200 ? 0 : 1);
        } catch (Exception e) {
            System.exit(1);
        }
    }
}
