package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Universal webhook capture: any request to /api/v1/dlr/receive is stored exactly, then interpreted. */
class WebhookCaptureIntegrationTest extends AbstractIntegrationTest {

    private static final String RECEIVE = "/api/v1/dlr/receive";

    // ------------------------------------------------------------------ helpers

    private HttpResponse<byte[]> send(String method, String path, String contentType, byte[] body,
                                      Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        headers.forEach(b::header);
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<byte[]> post(String contentType, String body) {
        return send("POST", RECEIVE, contentType, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static String captureId(HttpResponse<?> r) {
        return r.headers().firstValue("X-Capture-Id").orElseThrow(() -> new AssertionError("no X-Capture-Id"));
    }

    private JsonNode capture(String id) {
        return getJson("/api/v1/webhooks/requests/" + id);
    }

    private byte[] raw(String id) {
        return send("GET", "/api/v1/webhooks/requests/" + id + "/raw", null, null, Map.of()).body();
    }

    private static String text(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ any format is captured

    @Test
    void unknownJsonObjectIsCapturedAndRetrievable() {
        String body = "{\"event\":\"thing_happened\",\"id\":\"evt-1\",\"data\":{\"x\":1}}";
        HttpResponse<byte[]> r = post("application/json", body);
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode ack = readJson(text(r));
        assertThat(ack.get("captured").asBoolean()).isTrue();
        assertThat(ack.get("interpretation_status").asText()).isEqualTo("UNRECOGNIZED");
        assertThat(ack.get("capture_id").asText()).isEqualTo(captureId(r));

        JsonNode c = capture(captureId(r));
        assertThat(c.get("method").asText()).isEqualTo("POST");
        assertThat(c.get("path").asText()).isEqualTo(RECEIVE);
        assertThat(c.get("content_type").asText()).isEqualTo("application/json");
        assertThat(c.get("body_size_bytes").asLong()).isEqualTo(body.length());
        assertThat(c.get("body_text").asText()).isEqualTo(body);
        assertThat(c.get("interpretation_status").asText()).isEqualTo("UNRECOGNIZED");
        // "id" is the provider's event id, not a message id
        assertThat(c.get("extracted_message_id").isNull()).isTrue();
        assertThat(c.get("normalized_status").isNull()).isTrue();
        assertThat(new String(raw(captureId(r)), StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events", Integer.class)).isZero();
    }

    @Test
    void arraysPrimitivesTextXmlFormBinaryAndEmptyBodiesAreAllCaptured() {
        record Case(String contentType, byte[] body, String expected) {
        }
        List<Case> cases = List.of(
                new Case("application/json", "[{\"a\":1},{\"b\":2}]".getBytes(StandardCharsets.UTF_8), "UNRECOGNIZED"),
                new Case("application/json", "42".getBytes(StandardCharsets.UTF_8), "UNRECOGNIZED"),
                new Case("application/json", "\"just a string\"".getBytes(StandardCharsets.UTF_8), "UNRECOGNIZED"),
                new Case("application/json", "null".getBytes(StandardCharsets.UTF_8), "UNRECOGNIZED"),
                new Case("application/json", "{\"a\":1,".getBytes(StandardCharsets.UTF_8), "MALFORMED_JSON"),
                new Case("text/plain", "status=ok for 919000000001".getBytes(StandardCharsets.UTF_8), "NOT_JSON"),
                new Case("application/xml", "<dlr><id>1</id></dlr>".getBytes(StandardCharsets.UTF_8), "NOT_JSON"),
                new Case("application/x-www-form-urlencoded", "message_id=f-1&status=DELIVRD".getBytes(StandardCharsets.UTF_8), "NOT_JSON"),
                new Case("application/octet-stream", new byte[]{(byte) 0xff, (byte) 0xfe, 0, 1, 2, (byte) 0x80}, "NOT_JSON"),
                new Case(null, new byte[0], "EMPTY"));
        for (Case k : cases) {
            HttpResponse<byte[]> r = send("POST", RECEIVE, k.contentType(), k.body(), Map.of());
            assertThat(r.statusCode()).as(k.contentType() + " " + k.expected()).isEqualTo(200);
            String id = captureId(r);
            assertThat(r.headers().firstValue("X-Interpretation-Status")).contains(k.expected());
            assertThat(capture(id).get("interpretation_status").asText()).isEqualTo(k.expected());
            assertThat(raw(id)).as("exact bytes for " + k.contentType()).isEqualTo(k.body());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_requests", Integer.class)).isEqualTo(cases.size());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events", Integer.class)).isZero();

        // binary bodies are kept as bytes, reported as base64 in the detail
        JsonNode bin = getJson("/api/v1/webhooks/requests?q=&status=NOT_JSON&limit=10");
        assertThat(bin.get("items")).hasSize(4);
        // the form body was not consumed by the servlet container: its message id was extracted
        assertThat(getJson("/api/v1/webhooks/requests?message_id=f-1").get("items")).hasSize(1);
    }

    @Test
    void anyMethodIsCapturedAndMetaVerificationIsNot() {
        assertThat(send("PUT", RECEIVE, "application/json", "{\"p\":1}".getBytes(), Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("PATCH", RECEIVE, "text/plain", "x".getBytes(), Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("GET", RECEIVE + "?ping=1", null, null, Map.of()).statusCode()).isEqualTo(200);
        assertThat(get(RECEIVE + "?hub.mode=subscribe&hub.challenge=c&hub.verify_token=wrong").statusCode()).isEqualTo(403);
        List<String> methods = jdbc.queryForList("SELECT http_method FROM webhook_requests ORDER BY id", String.class);
        assertThat(methods).containsExactly("PUT", "PATCH", "GET");
    }

    // ------------------------------------------------------------------ exactness

    @Test
    void rawBodyIsPreservedByteForByte() {
        // duplicate keys, odd whitespace, escapes, unicode, CRLF line endings, key order
        String body = "{ \"z\" : 1,\r\n  \"a\":\"x\\u00e9\\/y\",\t\"a\": \"dup\" ,\"n\":1.50e+3, \"s\":\"नमस्ते 😀\"}\r\n";
        HttpResponse<byte[]> r = post("application/json; charset=utf-8", body);
        String id = captureId(r);
        assertThat(raw(id)).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        byte[] stored = jdbc.queryForObject("SELECT raw_body FROM webhook_requests WHERE capture_id = ?::uuid",
                byte[].class, id);
        assertThat(stored).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        assertThat(capture(id).get("body_text").asText()).isEqualTo(body);

        // other charsets are kept as sent and decoded with the declared charset for display
        byte[] latin = "{\"msg\":\"café\"}".getBytes(StandardCharsets.ISO_8859_1);
        HttpResponse<byte[]> r2 = send("POST", RECEIVE, "application/json; charset=ISO-8859-1", latin, Map.of());
        assertThat(raw(captureId(r2))).isEqualTo(latin);
        assertThat(capture(captureId(r2)).get("body_text").asText()).isEqualTo("{\"msg\":\"café\"}");
    }

    @Test
    void knownDlrIsStillInterpretedAndCaptured() {
        String body = TestPayloads.defaultSms("cap-known", "DELIVRD");
        HttpResponse<byte[]> r = send("POST", RECEIVE, "application/json", body.getBytes(StandardCharsets.UTF_8),
                Map.of("X-DLR-Source", "DEFAULT_SMS"));
        JsonNode ack = readJson(text(r));
        assertThat(ack.get("processing_status").asText()).isEqualTo("APPLIED");
        assertThat(ack.get("interpretation_status").asText()).isEqualTo("INTERPRETED");
        JsonNode c = capture(captureId(r));
        assertThat(c.get("detected_source").asText()).isEqualTo("DEFAULT_SMS");
        assertThat(c.get("extracted_message_id").asText()).isEqualTo("cap-known");
        assertThat(c.get("normalized_status").asText()).isEqualTo("DELIVERED");
        assertThat(c.get("dlr_event_id").asLong()).isPositive();
        assertThat(getJson("/api/v1/dlr/cap-known").get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    void queryParametersAndHeadersAreCapturedWithSecretsMasked() {
        HttpResponse<byte[]> r = send("POST", RECEIVE + "?foo=1&foo=2&bar=a%20b&flag", "application/json",
                "{}".getBytes(), Map.of("X-Custom-Trace", "trace-123", "Authorization", "Bearer secret-token",
                        "X-API-Key", "k-secret"));
        JsonNode c = capture(captureId(r));
        assertThat(c.get("query_string").asText()).isEqualTo("foo=1&foo=2&bar=a%20b&flag");
        assertThat(c.at("/query_params/foo").toString()).isEqualTo("[\"1\",\"2\"]");
        assertThat(c.at("/query_params/bar/0").asText()).isEqualTo("a b");
        assertThat(c.at("/query_params/flag/0").asText()).isEmpty();
        assertThat(c.at("/headers/x-custom-trace/0").asText()).isEqualTo("trace-123");
        assertThat(c.at("/headers/authorization/0").asText()).isEqualTo("***");
        assertThat(c.at("/headers/x-api-key/0").asText()).isEqualTo("***");
        assertThat(jdbc.queryForObject("SELECT headers::text FROM webhook_requests", String.class))
                .doesNotContain("secret-token").doesNotContain("k-secret");
        assertThat(c.get("remote_address").asText()).isNotBlank();
    }

    // ------------------------------------------------------------------ message ids / automation contracts

    @Test
    void sameMessageIdInSeveralFormatsIsFoundAndNeverCountsAsDelivered() {
        post("application/json", "{\"msgId\":\"multi-1\",\"deliveryStatus\":\"DELIVERED\"}");
        send("POST", RECEIVE, "application/x-www-form-urlencoded",
                "message_id=multi-1%3A2&status=DELIVRD".getBytes(), Map.of());
        post("application/json", "{\"wrapper\":{\"inner\":{\"message_id\":\"multi-1\"}}}");

        JsonNode list = getJson("/api/v1/webhooks/requests?message_id=multi-1");
        assertThat(list.get("items")).hasSize(3);
        // "<id>:<n>" finds the recipient's own capture
        assertThat(getJson("/api/v1/webhooks/requests?message_id=multi-1:2").get("items")).hasSize(1);
        assertThat(list.at("/items/2/provider_status").asText()).isEqualTo("DELIVERED");   // exact, not normalized
        assertThat(list.at("/items/2/normalized_status").isNull()).isTrue();

        // automation: still not received, verify must not pass
        JsonNode s = getJson("/api/v1/dlr/multi-1");
        assertThat(s.get("received").asBoolean()).isFalse();
        assertThat(s.get("status").asText()).isEqualTo("PENDING");
        assertThat(s.at("/captures/total").asInt()).isEqualTo(3);
        JsonNode v = readJson(postDlr("/api/v1/dlr/verify",
                "{\"message_ids\":[\"multi-1\"],\"expected_status\":\"DELIVERED\"}", Map.of()).body());
        assertThat(v.get("all_matched").asBoolean()).isFalse();
        assertThat(v.at("/results/0/received").asBoolean()).isFalse();

        // the /json API falls back to the latest uninterpreted capture, exactly as sent
        HttpResponse<String> j = get("/api/v1/dlr/multi-1/json");
        assertThat(j.statusCode()).isEqualTo(200);
        assertThat(j.headers().firstValue("X-DLR-Processing-Status")).contains("UNRECOGNIZED");
        assertThat(j.body()).isEqualTo("{\"wrapper\":{\"inner\":{\"message_id\":\"multi-1\"}}}");

        JsonNode latest = getJson("/api/v1/webhooks/requests/latest?message_id=multi-1");
        assertThat(latest.get("body_text").asText()).contains("wrapper");
    }

    @Test
    void payloadWithoutMessageIdOrWithUnknownStatusIsStillCaptured() {
        HttpResponse<byte[]> r = post("application/json", "{\"status\":\"WEIRD_STATE\",\"to\":\"919812345678\"}");
        JsonNode c = capture(captureId(r));
        assertThat(c.get("extracted_message_id").isNull()).isTrue();
        assertThat(c.get("provider_status").asText()).isEqualTo("WEIRD_STATE");
        assertThat(c.get("extracted_recipient").asText()).isEqualTo("919812345678");
        assertThat(c.get("normalized_status").isNull()).isTrue();
    }

    @Test
    void duplicatesAreEachCapturedWhileTheDlrIsDeduplicated() {
        String body = TestPayloads.defaultSms("dup-cap", "DELIVRD");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            ids.add(captureId(send("POST", RECEIVE, "application/json", body.getBytes(), Map.of("X-DLR-Source", "DEFAULT_SMS"))));
        }
        assertThat(ids).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_requests", Integer.class)).isEqualTo(3);
        assertThat(getJson("/api/v1/dlr/dup-cap").get("duplicate_count").asInt()).isEqualTo(2);
    }

    @Test
    void concurrentRequestsAreAllCaptured() throws Exception {
        int n = 40;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<HttpResponse<byte[]>>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String b = i % 2 == 0 ? "{\"unknown\":" + i + "}" : TestPayloads.defaultSms("cc-" + (i % 5), "DELIVRD");
                fs.add(pool.submit(() -> send("POST", RECEIVE, "application/json", b.getBytes(), Map.of())));
            }
            Set<String> ids = new HashSet<>();
            for (Future<HttpResponse<byte[]>> f : fs) {
                HttpResponse<byte[]> r = f.get();
                assertThat(r.statusCode()).isEqualTo(200);
                ids.add(captureId(r));
            }
            assertThat(ids).hasSize(n);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_requests", Integer.class)).isEqualTo(n);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_requests WHERE interpretation_status = 'PENDING'",
                Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ list / search / cursor

    @Test
    void listSupportsCursorFiltersAndSearch() {
        post("application/json", "{\"a\":\"needle-in-body\"}");
        send("POST", RECEIVE, "text/plain", "plain".getBytes(), Map.of("X-Trace", "header-needle"));
        JsonNode first = getJson("/api/v1/webhooks/requests");
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.at("/items/0/content_type").asText()).isEqualTo("text/plain");   // newest first
        long cursor = first.get("cursor").asLong();

        String newest = captureId(post("application/json", "{broken"));
        JsonNode next = getJson("/api/v1/webhooks/requests?after_id=" + cursor);
        assertThat(next.get("items")).hasSize(1);
        assertThat(next.at("/items/0/capture_id").asText()).isEqualTo(newest);
        assertThat(getJson("/api/v1/webhooks/requests?after_id=" + next.get("cursor").asLong()).get("items")).isEmpty();

        assertThat(getJson("/api/v1/webhooks/requests?status=MALFORMED_JSON").get("items")).hasSize(1);
        assertThat(getJson("/api/v1/webhooks/requests?status=unrecognized").get("items")).hasSize(2);
        assertThat(getJson("/api/v1/webhooks/requests/search?q=needle-in-body").get("items")).hasSize(1);
        assertThat(getJson("/api/v1/webhooks/requests/search?q=header-needle").get("items")).hasSize(1);
        assertThat(getJson("/api/v1/webhooks/requests/search?q=" + newest).get("items")).hasSize(1);
        assertThat(getJson("/api/v1/webhooks/requests/search?q=100%25_x").get("items")).isEmpty();   // LIKE escaping

        JsonNode page = getJson("/api/v1/webhooks/requests?limit=2");
        assertThat(page.get("items")).hasSize(2);
        JsonNode older = getJson("/api/v1/webhooks/requests?limit=2&before_id=" + page.get("next_before_id").asLong());
        assertThat(older.get("items")).hasSize(1);

        assertThat(get("/api/v1/webhooks/requests/not-a-uuid").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/webhooks/requests/00000000-0000-0000-0000-000000000000").statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ failure handling / retention

    @Test
    void databaseFailureIsARetryable503AndNothingIsAcknowledged() {
        jdbc.execute("ALTER TABLE webhook_requests RENAME TO webhook_requests_off");
        try {
            HttpResponse<byte[]> r = post("application/json", "{\"anything\":1}");
            assertThat(r.statusCode()).isEqualTo(503);
            assertThat(r.headers().firstValue("X-Capture-Id")).isEmpty();
        } finally {
            jdbc.execute("ALTER TABLE webhook_requests_off RENAME TO webhook_requests");
        }
        assertThat(post("application/json", "{\"anything\":1}").statusCode()).isEqualTo(200);
    }

    @Test
    void capturedRequestIsImmutable() {
        String id = captureId(post("application/json", "{\"x\":1}"));
        try {
            jdbc.update("UPDATE webhook_requests SET raw_body = 'changed'::bytea WHERE capture_id = ?::uuid", id);
            throw new AssertionError("raw_body update must be refused");
        } catch (org.springframework.dao.DataAccessException expected) {
            assertThat(expected.getMessage()).contains("immutable");
        }
        assertThat(new String(raw(id), StandardCharsets.UTF_8)).isEqualTo("{\"x\":1}");
    }

    @Test
    void retentionDeletesOldCaptures() {
        post("application/json", "{\"old\":true}");
        post("application/json", "{\"new\":true}");
        jdbc.execute("ALTER TABLE webhook_requests DISABLE TRIGGER trg_webhook_requests_immutable");
        try {
            jdbc.update("UPDATE webhook_requests SET received_at = received_at - interval '8 days' "
                    + "WHERE body_text LIKE '%old%'");
        } finally {
            jdbc.execute("ALTER TABLE webhook_requests ENABLE TRIGGER trg_webhook_requests_immutable");
        }
        JsonNode r = readJson(postDlr("/api/v1/dlr/retention/run", "", Map.of()).body());
        assertThat(r.at("/deleted/webhook_requests").asLong()).isEqualTo(1);
        // the retention run itself was a request to another path: only the recent capture remains
        assertThat(jdbc.queryForList("SELECT body_text FROM webhook_requests", String.class))
                .containsExactly("{\"new\":true}");
    }
}
