package com.paytm.wallet.obs;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Assigns every request a correlation id (honouring an inbound {@code X-Correlation-Id} /
 * {@code X-Request-Id} header when present), exposes it via SLF4J {@link MDC} so every log line
 * carries it, echoes it back on the response, and logs one structured {@code http.request} event
 * per request with method, path, status and duration.
 */
@Component
@Order(CorrelationIdFilter.ORDER)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Must run before {@link com.paytm.wallet.auth.BearerAuthFilter} so 401s are correlated too. */
    public static final int ORDER = -100;

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";

    private static final Logger log = LoggerFactory.getLogger("http");
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = firstValid(
                request.getHeader(HEADER),
                request.getHeader("X-Request-Id"));
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("http.request",
                    kv("event", "http.request"),
                    kv("method", request.getMethod()),
                    kv("path", request.getRequestURI()),
                    kv("status", response.getStatus()),
                    kv("duration_ms", durationMs));
            MDC.remove(MDC_KEY);
        }
    }

    private static String firstValid(String... candidates) {
        for (String c : candidates) {
            if (c != null && SAFE.matcher(c).matches()) {
                return c;
            }
        }
        return null;
    }
}
