package com.smsframework.dlr.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smsframework.dlr.support.AbstractIntegrationTest;
import com.smsframework.dlr.support.TestPayloads;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Volume + correlation: 1, 100, 1,000 and 10,000 DLRs sent concurrently over HTTP, mixing providers,
 * statuses, status progressions and duplicates. Every message must end with exactly the expected
 * status, mobile and correlation id: no cross-talk between messages.
 *
 * Tagged "bulk" (run everything with `mvn verify`; skip with `mvn verify -Pfast`).
 */
@Tag("bulk")
class DlrBulkIntegrationTest extends AbstractIntegrationTest {

    private static final int CONCURRENCY = 48;

    private record Expectation(String messageId, String status, String mobile, String correlationId, String source) {
    }

    @ParameterizedTest(name = "{0} DLRs")
    @ValueSource(ints = {1, 100, 1_000, 10_000})
    void bulkDlrsAreCorrelatedToTheRightMessage(int count) throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<String[]> calls = new ArrayList<>(); // {source, body}
        Map<String, Expectation> expected = new HashMap<>();

        for (int i = 0; i < count; i++) {
            String id = "bulk-" + run + "-" + i;
            String mobile = "91" + String.format("%010d", i);
            String corr = "corr-" + run + "-" + i;
            switch (i % 4) {
                case 0 -> { // default SMS delivered (+ duplicate every 10th)
                    String body = TestPayloads.defaultSms(id, mobile, "DELIVRD", "000", "2026-06-22 11:47:33", corr);
                    calls.add(new String[]{"DEFAULT_SMS", body});
                    if (i % 10 == 0) {
                        calls.add(new String[]{"DEFAULT_SMS", body});
                    }
                    expected.put(id, new Expectation(id, "DELIVERED", mobile, corr, "DEFAULT_SMS"));
                }
                case 1 -> { // default SMS failed
                    calls.add(new String[]{"DEFAULT_SMS",
                            TestPayloads.defaultSms(id, mobile, "UNDELIV", "034", "2026-06-22 11:47:33", corr)});
                    expected.put(id, new Expectation(id, "FAILED", mobile, corr, "DEFAULT_SMS"));
                }
                case 2 -> { // WebEngage sent -> delivered (sent concurrently, order not guaranteed)
                    calls.add(new String[]{"WEBENGAGE", webEngage(id, mobile, "sms_sent")});
                    calls.add(new String[]{"WEBENGAGE", webEngage(id, mobile, "sms_delivered")});
                    expected.put(id, new Expectation(id, "DELIVERED", mobile, null, "WEBENGAGE"));
                }
                default -> { // WebEngage sent only
                    calls.add(new String[]{"WEBENGAGE", webEngage(id, mobile, "sms_sent")});
                    expected.put(id, new Expectation(id, "SENT", mobile, null, "WEBENGAGE"));
                }
            }
        }

        // Fire all callbacks concurrently
        Semaphore permits = new Semaphore(CONCURRENCY);
        AtomicInteger non200 = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>(calls.size());
        for (String[] c : calls) {
            permits.acquire();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url("/api/v1/dlr/receive")))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("X-DLR-Source", c[0])
                    .POST(HttpRequest.BodyPublishers.ofString(c[1]))
                    .build();
            futures.add(http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(r -> {
                        if (r.statusCode() != 200) {
                            non200.incrementAndGet();
                        }
                    })
                    .whenComplete((r, e) -> permits.release()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        assertThat(non200.get()).isZero();

        // Verify through the automation API (what pytest uses)
        ObjectNode req = JSON.createObjectNode();
        ArrayNode ids = req.putArray("message_ids");
        expected.keySet().forEach(ids::add);
        JsonNode verify = readJson(postDlr("/api/v1/dlr/verify", req.toString(), Map.of()).body());
        assertThat(verify.get("total").asInt()).isEqualTo(count);
        assertThat(verify.get("received").asInt()).isEqualTo(count);
        assertThat(verify.get("missing").asInt()).isZero();
        for (JsonNode r : verify.get("results")) {
            Expectation e = expected.get(r.get("message_id").asText());
            assertThat(r.get("status").asText()).as(e.messageId()).isEqualTo(e.status());
            assertThat(r.get("source").asText()).as(e.messageId()).isEqualTo(e.source());
        }

        // Verify correlation straight from the database: every row carries its own mobile / correlation_id
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT message_id, mobile, correlation_id, normalized_status FROM dlr_message_status WHERE message_id LIKE ?",
                "bulk-" + run + "-%");
        assertThat(rows).hasSize(count);
        for (Map<String, Object> row : rows) {
            Expectation e = expected.get((String) row.get("message_id"));
            assertThat(row.get("mobile")).as(e.messageId()).isEqualTo(e.mobile());
            assertThat(row.get("correlation_id")).as(e.messageId()).isEqualTo(e.correlationId());
            assertThat(row.get("normalized_status")).as(e.messageId()).isEqualTo(e.status());
        }

        int expectedDuplicates = (int) java.util.stream.IntStream.range(0, count).filter(i -> i % 4 == 0 && i % 10 == 0).count();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlr_events WHERE processing_status='DUPLICATE' AND message_id LIKE ?",
                Integer.class, "bulk-" + run + "-%")).isEqualTo(expectedDuplicates);
    }

    private static String webEngage(String id, String mobile, String status) {
        return """
                {"version":"1.0","messageId":"%s","toNumber":"%s","status":"%s","statusCode":"0","smsCount":"1"}"""
                .formatted(id, mobile, status);
    }
}
