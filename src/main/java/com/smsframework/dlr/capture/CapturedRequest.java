package com.smsframework.dlr.capture;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One HTTP request exactly as it reached the callback endpoint.
 *
 * @param body         the original bytes, or null when the body was larger than the limit (bodyTruncated)
 * @param bodyText     body decoded with {@code bodyEncoding}, or null when it is not valid text (binary)
 * @param bodySize     size of the body in bytes (for an oversized body: as far as it was read / Content-Length)
 */
public record CapturedRequest(
        long id,
        UUID captureId,
        OffsetDateTime receivedAt,
        String method,
        String path,
        String queryString,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> headers,
        String contentType,
        byte[] body,
        long bodySize,
        boolean bodyTruncated,
        String bodyEncoding,
        String bodyText,
        String remoteAddress) {

    public CapturedRequest withId(long newId, OffsetDateTime at) {
        return new CapturedRequest(newId, captureId, at, method, path, queryString, queryParams, headers, contentType,
                body, bodySize, bodyTruncated, bodyEncoding, bodyText, remoteAddress);
    }
}
