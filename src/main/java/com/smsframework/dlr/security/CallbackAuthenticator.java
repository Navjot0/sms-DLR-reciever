package com.smsframework.dlr.security;

import com.smsframework.dlr.config.DlrProperties;
import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates every ENABLED mechanism (API key, bearer token, IP allowlist, HMAC); all enabled
 * mechanisms must pass. Secrets are compared in constant time. Nothing is hard-coded: every
 * credential comes from configuration / environment.
 */
public class CallbackAuthenticator {

    public record Failure(int httpStatus, String mechanism, String message) {
    }

    private final DlrProperties.Security config;
    private final IpAllowlist ipAllowlist;
    private final Clock clock;

    public CallbackAuthenticator(DlrProperties.Security config, Clock clock) {
        this.config = config;
        this.ipAllowlist = new IpAllowlist(config.getIpAllowlist().getCidrs());
        this.clock = clock;
    }

    public Optional<Failure> authenticate(HttpServletRequest request, byte[] body) {
        if (!config.isEnabled()) {
            return Optional.empty();
        }
        if (config.getIpAllowlist().isEnabled()) {
            String ip = clientIp(request);
            if (!ipAllowlist.allows(ip)) {
                return Optional.of(new Failure(403, "ip_allowlist", "source IP not allowed"));
            }
        }
        if (config.getApiKey().isEnabled()) {
            String key = request.getHeader(config.getApiKey().getHeaderName());
            if (!matchesAny(key, config.getApiKey().getKeys())) {
                return Optional.of(new Failure(401, "api_key", "missing or invalid API key"));
            }
        }
        if (config.getBearer().isEnabled()) {
            String auth = request.getHeader("Authorization");
            String token = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : null;
            if (!matchesAny(token, config.getBearer().getTokens())) {
                return Optional.of(new Failure(401, "bearer", "missing or invalid bearer token"));
            }
        }
        if (config.getHmac().isEnabled()) {
            Optional<String> hmacError = verifyHmac(request, body);
            if (hmacError.isPresent()) {
                return Optional.of(new Failure(401, "hmac", hmacError.get()));
            }
        }
        return Optional.empty();
    }

    /** API-key check for query endpoints when dlr.security.protect-query-api=true. */
    public boolean queryApiKeyValid(HttpServletRequest request) {
        return matchesAny(request.getHeader(config.getApiKey().getHeaderName()), config.getApiKey().getKeys());
    }

    private Optional<String> verifyHmac(HttpServletRequest request, byte[] body) {
        DlrProperties.Hmac h = config.getHmac();
        String signature = request.getHeader(h.getHeaderName());
        if (signature == null || signature.isBlank()) {
            return Optional.of("missing signature header " + h.getHeaderName());
        }
        String timestamp = h.getTimestampHeaderName() == null ? null : request.getHeader(h.getTimestampHeaderName());
        if (timestamp == null && h.isRequireTimestamp()) {
            return Optional.of("missing timestamp header " + h.getTimestampHeaderName());
        }
        byte[] signed = body;
        if (timestamp != null) {
            long ts;
            try {
                ts = Long.parseLong(timestamp.trim());
            } catch (NumberFormatException e) {
                return Optional.of("invalid timestamp header");
            }
            long nowSeconds = clock.millis() / 1000;
            long tsSeconds = ts > 100_000_000_000L ? ts / 1000 : ts; // accept seconds or millis
            if (Math.abs(nowSeconds - tsSeconds) > h.getMaxSkewSeconds()) {
                return Optional.of("timestamp outside allowed skew");
            }
            byte[] prefix = (timestamp.trim() + ".").getBytes(StandardCharsets.UTF_8);
            signed = new byte[prefix.length + body.length];
            System.arraycopy(prefix, 0, signed, 0, prefix.length);
            System.arraycopy(body, 0, signed, prefix.length, body.length);
        }
        byte[] expected = hmac(h.getAlgorithm(), h.getSecret(), signed);
        String provided = signature.trim();
        int eq = provided.indexOf('=');
        if (eq > 0 && provided.substring(0, eq).toLowerCase().startsWith("sha")) {
            provided = provided.substring(eq + 1); // "sha256=<hex>" form
        }
        byte[] providedBytes = decodeSignature(provided);
        if (providedBytes == null || !MessageDigest.isEqual(expected, providedBytes)) {
            return Optional.of("invalid signature");
        }
        return Optional.empty();
    }

    public static byte[] hmac(String algorithm, String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    /** Accepts lowercase/uppercase hex or base64. */
    private static byte[] decodeSignature(String s) {
        try {
            if (s.matches("[0-9a-fA-F]+") && s.length() % 2 == 0) {
                return HexFormat.of().parseHex(s.toLowerCase());
            }
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String clientIp(HttpServletRequest request) {
        if (config.getIpAllowlist().isTrustForwardedFor()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private static boolean matchesAny(String provided, List<String> accepted) {
        if (provided == null || provided.isBlank() || accepted == null) {
            return false;
        }
        byte[] p = provided.trim().getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        for (String a : accepted) {
            if (a != null && !a.isBlank() && MessageDigest.isEqual(p, a.trim().getBytes(StandardCharsets.UTF_8))) {
                ok = true; // no early exit: keep timing independent of which key matched
            }
        }
        return ok;
    }
}
