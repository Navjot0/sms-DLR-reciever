package com.smsframework.dlr.support;

/** Sample provider payloads used across tests (mirrors samples/ in the repo root). */
public final class TestPayloads {

    private TestPayloads() {
    }

    public static final String DEFAULT_SMS_EXAMPLE = """
            {
              "payload": {
                "message_id": "2ee98174-eec2-46b1-9b3c-baa0853c9538",
                "service": "T",
                "sender": "MSEFSL",
                "mobile": "917973059161",
                "status": "DELIVRD",
                "code": "000",
                "submit_at": "2026-06-22 11:47:33",
                "dlr_received_at": "2026-06-22 11:47:33",
                "entity_id": "",
                "template_id": "1507165786055955979",
                "units": "2",
                "correlation_id": "75892985798379875987198579175987912757589298579837987598719857917598791275"
              }
            }""";

    public static final String WEBENGAGE_EXAMPLE = """
            {
              "version": "1.0",
              "messageId": "f1189190-3fab-4a74-9130-f932be1de679",
              "toNumber": "919014305913",
              "status": "sms_sent",
              "statusCode": "0",
              "smsCount": "3"
            }""";

    public static String defaultSms(String messageId, String mobile, String status, String code,
                                    String dlrReceivedAt, String correlationId) {
        return """
                {"payload":{"message_id":"%s","service":"T","sender":"MSEFSL","mobile":"%s","status":"%s","code":"%s",
                "submit_at":"2026-06-22 11:47:33","dlr_received_at":"%s","entity_id":"","template_id":"1507165786055955979",
                "units":"1","correlation_id":"%s"}}""".formatted(messageId, mobile, status, code, dlrReceivedAt, correlationId);
    }

    public static String defaultSms(String messageId, String status) {
        return defaultSms(messageId, "917973059161", status, "000", "2026-06-22 11:47:33", "corr-" + messageId);
    }

    public static String webEngage(String messageId, String status, String statusCode) {
        return """
                {"version":"1.0","messageId":"%s","toNumber":"919014305913","status":"%s","statusCode":"%s","smsCount":"3"}"""
                .formatted(messageId, status, statusCode);
    }

    /** Flat default-SMS DLR (no "payload" wrapper), exactly as the live gateway sends it. */
    public static final String DEFAULT_SMS_FLAT_EXAMPLE = """
            {"code": "000", "units": "2", "mobile": "918727973019", "sender": "CERFGS", "status": "DELIVRD",
             "service": "T", "entity_id": "1701164872369547174", "submit_at": "2026-09-25 17:43:10",
             "message_id": "664f1ac5-3f1b-4a2b-b9e3-08c89b13b8a4", "template_id": "1507166245188685280",
             "correlation_id": null, "dlr_received_at": "2026-09-25 17:43:10"}""";

    public static final String BILLING_EXAMPLE = """
            {
              "event_type": "billing",
              "events": [
                {
                  "transaction_type": "debit",
                  "message_id": "9b1b0309-4d49-48be-b2c1-0283892ded9e:1",
                  "product": "SMS Transactional",
                  "units": 1,
                  "sale_price": 1,
                  "currency": "INR",
                  "surcharge": 0,
                  "total_amount": 1
                }
              ]
            }""";

    /** Billing callback with one debit event per part: "<messageId>:1" .. "<messageId>:<parts>". */
    public static String billing(String messageId, int parts, String transactionType, String amountPerPart) {
        StringBuilder events = new StringBuilder();
        for (int p = 1; p <= parts; p++) {
            if (p > 1) {
                events.append(',');
            }
            events.append("""
                    {"transaction_type":"%s","message_id":"%s:%d","product":"SMS Transactional","units":1,
                    "sale_price":%s,"currency":"INR","surcharge":0,"total_amount":%s}"""
                    .formatted(transactionType, messageId, p, amountPerPart, amountPerPart));
        }
        return "{\"event_type\":\"billing\",\"events\":[" + events + "]}";
    }
}
