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
}
