package com.smsframework.dlr.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsframework.dlr.security.CallbackAuthenticationFilter;
import com.smsframework.dlr.security.CallbackAuthenticator;
import com.smsframework.dlr.security.IpAllowlist;
import com.smsframework.dlr.service.DlrMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
 * Wiring + fail-fast validation of security configuration.
 *
 * Production guard: when any profile in dlr.security.enforce-in-profiles (default: prod, production)
 * is active, the service refuses to start unless callback authentication is enabled and fully configured.
 */
@Configuration
public class DlrConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DlrConfiguration.class);

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public CallbackAuthenticator callbackAuthenticator(DlrProperties properties, Clock clock, Environment env) {
        validate(properties, env);
        return new CallbackAuthenticator(properties.getSecurity(), clock);
    }

    @Bean
    public FilterRegistrationBean<PathNormalizationFilter> pathNormalizationFilter() {
        FilterRegistrationBean<PathNormalizationFilter> reg = new FilterRegistrationBean<>(new PathNormalizationFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("dlrPathNormalizationFilter");
        return reg;
    }

    @Bean
    public FilterRegistrationBean<CallbackAuthenticationFilter> callbackAuthenticationFilter(
            DlrProperties properties, CallbackAuthenticator authenticator, DlrMetrics metrics, ObjectMapper objectMapper) {
        FilterRegistrationBean<CallbackAuthenticationFilter> reg = new FilterRegistrationBean<>(
                new CallbackAuthenticationFilter(properties, authenticator, metrics, objectMapper));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setName("dlrCallbackAuthenticationFilter");
        return reg;
    }

    static void validate(DlrProperties properties, Environment env) {
        DlrProperties.Security sec = properties.getSecurity();
        List<String> active = Arrays.asList(env.getActiveProfiles());
        boolean enforced = sec.getEnforceInProfiles().stream().anyMatch(active::contains);

        ZoneId.of(properties.getTimezone()); // fail fast on a bad zone id
        if (HttpStatus.resolve(properties.getApi().getRejectedHttpStatus()) == null) {
            throw new IllegalStateException("dlr.api.rejected-http-status is not a valid HTTP status");
        }

        if (!sec.isEnabled()) {
            if (enforced) {
                throw new IllegalStateException("DLR callback authentication is disabled but profile(s) " + active
                        + " require it. Set dlr.security.enabled=true (DLR_AUTH_ENABLED=true) and configure a mechanism.");
            }
            log.warn("DLR callback authentication is DISABLED (acceptable for local automation only)");
            return;
        }
        if (!sec.anyMechanismEnabled()) {
            throw new IllegalStateException("dlr.security.enabled=true but no mechanism (api-key, bearer, ip-allowlist, hmac) is enabled");
        }
        if (sec.getApiKey().isEnabled() && sec.getApiKey().getKeys().stream().allMatch(k -> k == null || k.isBlank())) {
            throw new IllegalStateException("dlr.security.api-key.enabled=true but no keys configured (DLR_API_KEYS)");
        }
        if (sec.isProtectQueryApi() && sec.getApiKey().getKeys().stream().allMatch(k -> k == null || k.isBlank())) {
            throw new IllegalStateException("dlr.security.protect-query-api=true requires dlr.security.api-key.keys");
        }
        if (sec.getBearer().isEnabled() && sec.getBearer().getTokens().stream().allMatch(t -> t == null || t.isBlank())) {
            throw new IllegalStateException("dlr.security.bearer.enabled=true but no tokens configured (DLR_BEARER_TOKENS)");
        }
        if (sec.getIpAllowlist().isEnabled() && new IpAllowlist(sec.getIpAllowlist().getCidrs()).isEmpty()) {
            throw new IllegalStateException("dlr.security.ip-allowlist.enabled=true but no CIDRs configured (DLR_IP_ALLOWLIST)");
        }
        if (sec.getHmac().isEnabled()) {
            String secret = sec.getHmac().getSecret();
            if (secret == null || secret.length() < 16) {
                throw new IllegalStateException("dlr.security.hmac.secret must be at least 16 characters (DLR_HMAC_SECRET)");
            }
            CallbackAuthenticator.hmac(sec.getHmac().getAlgorithm(), secret, new byte[0]); // validates algorithm
        }
        log.info("DLR callback authentication enabled: api_key={} bearer={} ip_allowlist={} hmac={}",
                sec.getApiKey().isEnabled(), sec.getBearer().isEnabled(), sec.getIpAllowlist().isEnabled(),
                sec.getHmac().isEnabled());
    }
}
