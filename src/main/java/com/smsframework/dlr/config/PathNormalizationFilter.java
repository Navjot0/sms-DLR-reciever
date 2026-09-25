package com.smsframework.dlr.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Collapses repeated slashes in the request path, so "http://host:8080//api/v1/dlr/{id}" (a base URL
 * configured with a trailing slash) resolves to the same endpoint as "/api/v1/dlr/{id}" instead of a 404.
 * Runs before every other filter so authentication sees the normalized path too.
 */
public class PathNormalizationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri == null || !uri.contains("//")) {
            chain.doFilter(request, response);
            return;
        }
        String normalized = uri.replaceAll("/{2,}", "/");
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getRequestURI() {
                return normalized;
            }

            @Override
            public StringBuffer getRequestURL() {
                StringBuffer url = new StringBuffer();
                url.append(getScheme()).append("://").append(getServerName());
                int port = getServerPort();
                if (port > 0 && !(("http".equals(getScheme()) && port == 80) || ("https".equals(getScheme()) && port == 443))) {
                    url.append(':').append(port);
                }
                return url.append(normalized);
            }
        }, response);
    }
}
