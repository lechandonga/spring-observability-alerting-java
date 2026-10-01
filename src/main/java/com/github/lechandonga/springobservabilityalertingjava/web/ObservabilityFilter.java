package com.github.lechandonga.springobservabilityalertingjava.web;

import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.metrics.RequestMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

/**
 * Records per-request metrics and feeds the alert engine. Runs after
 * {@link RequestIdFilter} so every log line carries the request id.
 *
 * <p>Recording happens in a finally block, so exceptions still produce an
 * observation (status 5xx) and the original exception propagates untouched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ObservabilityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityFilter.class);

    private final RequestMetrics metrics;
    private final AlertEngine alertEngine;
    private final Clock clock;

    public ObservabilityFilter(RequestMetrics metrics, AlertEngine alertEngine, Clock clock) {
        this.metrics = metrics;
        this.alertEngine = alertEngine;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            String endpoint = endpointOf(request);
            String statusClass = (response.getStatus() / 100) + "xx";
            var key = metrics.record(endpoint, statusClass, latencyMs);
            alertEngine.recordEvent(key.endpoint(), key.statusClass(), clock.instant());
            log.info("request method={} path={} status={} latencyMs={} series={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), latencyMs, key);
        }
    }

    private static String endpointOf(HttpServletRequest request) {
        // Use the route pattern, not the raw URI, to keep cardinality bounded.
        Object pattern = request.getAttribute(
                "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern");
        String path = pattern != null ? pattern.toString() : request.getRequestURI();
        return request.getMethod() + " " + path;
    }
}
