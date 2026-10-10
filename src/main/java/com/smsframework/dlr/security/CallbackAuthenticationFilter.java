package com.smsframework.dlr.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsframework.dlr.config.DlrProperties;
import com.smsframework.dlr.dto.ErrorResponse;
import com.smsframework.dlr.service.DlrMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Authenticates DLR callbacks before they reach the controller. The body is buffered (bounded by
 * dlr.api.max-payload-bytes + 1) so HMAC can be verified over the exact bytes received.
 */
public class CallbackAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CallbackAuthenticationFilter.class);
    private static final String QUERY_PREFIX = "/api/v1/dlr/";
    /** Captured webhook requests (raw bodies, headers): same protection as the DLR query API. */
    private static final String CAPTURE_PREFIX = "/api/v1/webhooks/";

    private final DlrProperties properties;
    private final CallbackAuthenticator authenticator;
    private final DlrMetrics metrics;
    private final ObjectMapper objectMapper;

    public CallbackAuthenticationFilter(DlrProperties properties, CallbackAuthenticator authenticator,
                                        DlrMetrics metrics, ObjectMapper objectMapper) {
        this.properties = properties;
        this.authenticator = authenticator;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        DlrProperties.Security sec = properties.getSecurity();

        boolean callback = sec.getCallbackPaths().contains(path);
        if (!sec.isEnabled() || !callback) {
            if (sec.isEnabled() && sec.isProtectQueryApi() && (path.startsWith(QUERY_PREFIX) || path.startsWith(CAPTURE_PREFIX))
                    && !authenticator.queryApiKeyValid(request)) {
                metrics.authFailed("query_api_key");
                write(response, 401, "missing or invalid API key");
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        // Read at most max+1 bytes: an oversized body is still passed on (and rejected + stored by the service).
        byte[] body = readBounded(request.getInputStream(), properties.getApi().getMaxPayloadBytes() + 1L);
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request, body);

        Optional<CallbackAuthenticator.Failure> failure = authenticator.authenticate(wrapped, body);
        if (failure.isPresent()) {
            CallbackAuthenticator.Failure f = failure.get();
            metrics.authFailed(f.mechanism());
            log.warn("DLR callback refused mechanism={} remote_addr={} reason=\"{}\"",
                    f.mechanism(), request.getRemoteAddr(), f.message());
            write(response, f.httpStatus(), f.message());
            return;
        }
        chain.doFilter(wrapped, response);
    }

    private void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(status, status == 403 ? "FORBIDDEN" : "UNAUTHORIZED", message));
    }

    private static byte[] readBounded(InputStream in, long limit) throws IOException {
        return in.readNBytes((int) Math.min(limit, Integer.MAX_VALUE - 8));
    }
}
