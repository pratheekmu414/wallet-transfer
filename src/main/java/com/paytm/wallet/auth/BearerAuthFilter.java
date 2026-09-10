package com.paytm.wallet.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the caller from an {@code Authorization: Bearer <token>} header.
 *
 * <p>Auth sophistication is explicitly out of scope for this exercise, so we keep it deliberately
 * minimal: the caller's user id is a stable, opaque derivation of the token
 * ({@code u_<sha256(token) prefix>}). No user table, no secret storage — a token simply <em>is</em>
 * an identity. Two requests with the same token are the same user; different tokens are different
 * users.
 */
@Component
@Order(BearerAuthFilter.ORDER)
public class BearerAuthFilter extends OncePerRequestFilter {

    /** Run after the correlation-id filter so auth failures are still correlated in the logs. */
    public static final int ORDER = 0;

    public static final String CALLER_ATTRIBUTE = "wallet.caller";

    private static final String BEARER_PREFIX = "Bearer ";

    private final ObjectMapper objectMapper;

    public BearerAuthFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/health")
                || path.equals("/metrics")
                || path.startsWith("/actuator")
                || path.startsWith("/admin"); // does its own ADMIN_TOKEN check
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response, "missing or malformed Authorization: Bearer header");
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            writeUnauthorized(response, "empty bearer token");
            return;
        }

        request.setAttribute(CALLER_ATTRIBUTE, new Caller(userIdFor(token)));
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(), Map.of("code", "unauthorized", "message", message));
    }

    static String userIdFor(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return "u_" + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
