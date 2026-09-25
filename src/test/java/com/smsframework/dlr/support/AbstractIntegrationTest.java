package com.smsframework.dlr.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Real HTTP server + real PostgreSQL. Connects to DLR_TEST_DB_URL (+ DLR_TEST_DB_USERNAME / DLR_TEST_DB_PASSWORD),
 * defaulting to jdbc:postgresql://localhost:5432/dlr_test with dlr/dlr. Flyway creates the schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String[] conn = {env("DLR_TEST_DB_URL", "jdbc:postgresql://localhost:5432/dlr_test"),
                env("DLR_TEST_DB_USERNAME", "dlr"), env("DLR_TEST_DB_PASSWORD", "dlr")};
        registry.add("spring.datasource.url", () -> conn[0]);
        registry.add("spring.datasource.username", () -> conn[1]);
        registry.add("spring.datasource.password", () -> conn[2]);
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE dlr_click_events, dlr_billing_events, dlr_message_status, dlr_events RESTART IDENTITY CASCADE");
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected HttpResponse<String> postDlr(String body, Map<String, String> headers) {
        return postDlr("/api/v1/dlr/receive", body, headers);
    }

    protected HttpResponse<String> postDlr(String path, String body, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        headers.forEach(b::header);
        return send(b.build());
    }

    protected HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(30)).GET().build());
    }

    protected JsonNode getJson(String path) {
        return readJson(get(path).body());
    }

    protected HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static JsonNode readJson(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("Not JSON: " + s, e);
        }
    }
}
