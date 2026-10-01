package com.github.lechandonga.springobservabilityalertingjava.metrics;

import com.github.lechandonga.springobservabilityalertingjava.alerting.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.requestid.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * 在请求出口统一记录指标并喂给告警引擎。异常被原样重新抛出，
 * 但指标仍按 5xx/ERROR 口径入账，保证异常流量也可观测。
 */
@Component
@Order(2)
public class MetricsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MetricsFilter.class);

    private final MetricsRegistry registry;
    private final AlertEngine alertEngine;

    public MetricsFilter(MetricsRegistry registry, AlertEngine alertEngine) {
        this.registry = registry;
        this.alertEngine = alertEngine;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        boolean error = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            error = true;
            throw ex;
        } finally {
            long latencyMillis = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            String endpoint = resolveEndpoint(request);
            int httpStatus = response.getStatus();
            error = error || httpStatus >= 500;
            String status = error && httpStatus < 400 ? "ERROR" : Integer.toString(httpStatus);
            MetricsRegistry.DimensionResult result =
                    registry.record(endpoint, status, latencyMillis, error);
            long tick = Instant.now().getEpochSecond();
            alertEngine.record(endpoint, status, 1L, error ? 1L : 0L, tick);
            log.info("metrics recorded, requestId={}, endpoint={}, status={}, latencyMillis={}, "
                            + "endpointOutcome={}, statusOutcome={}",
                    MDC.get(RequestIdFilter.MDC_KEY), endpoint, status, latencyMillis,
                    result.endpointOutcome(), result.statusOutcome());
        }
    }

    /** 优先使用 HandlerMapping 解析出的路径模板，避免 /users/1 /users/2 打爆维度基数。 */
    private String resolveEndpoint(HttpServletRequest request) {
        Object pattern = request.getAttribute(
                "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern");
        if (pattern instanceof String value && !value.isBlank()) {
            return value;
        }
        return request.getRequestURI();
    }
}
