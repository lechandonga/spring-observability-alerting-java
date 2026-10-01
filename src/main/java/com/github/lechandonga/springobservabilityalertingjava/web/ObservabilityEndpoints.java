package com.github.lechandonga.springobservabilityalertingjava.web;

import com.github.lechandonga.springobservabilityalertingjava.alerting.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.metrics.MetricsRegistry;
import com.github.lechandonga.springobservabilityalertingjava.metrics.MetricsSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量观测端点（不依赖 actuator）：
 * <ul>
 *   <li>{@code GET /observability/metrics}：指标一致性快照。</li>
 *   <li>{@code GET /observability/alerts/events}：告警评估流水，供复盘与断言。</li>
 * </ul>
 */
@RestController
@RequestMapping("/observability")
public class ObservabilityEndpoints {

    private final MetricsRegistry metricsRegistry;
    private final AlertEngine alertEngine;

    public ObservabilityEndpoints(MetricsRegistry metricsRegistry, AlertEngine alertEngine) {
        this.metricsRegistry = metricsRegistry;
        this.alertEngine = alertEngine;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        MetricsSnapshot snapshot = metricsRegistry.snapshot();
        List<Map<String, Object>> series = snapshot.series().stream()
                .map(this::renderSeries)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("invalidDimensionRejections", snapshot.invalidDimensionRejections());
        body.put("cappedDimensionMerges", snapshot.cappedDimensionMerges());
        body.put("maxSeries", snapshot.maxSeries());
        body.put("series", series);
        return body;
    }

    @GetMapping("/alerts/events")
    public Object alertEvents() {
        return Map.of("events", alertEngine.eventHistory(), "rules", alertEngine.currentRules());
    }

    private Map<String, Object> renderSeries(MetricsSnapshot.SeriesSnapshot series) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("endpoint", series.endpoint());
        rendered.put("status", series.status());
        rendered.put("endpointCapped", series.endpointCapped());
        rendered.put("statusCapped", series.statusCapped());
        rendered.put("calls", series.calls());
        rendered.put("errorCalls", series.errorCalls());
        rendered.put("latency", Map.of(
                "count", series.latency().count(),
                "sumMillis", series.latency().sum(),
                "minMillis", series.latency().minMillis(),
                "maxMillis", series.latency().maxMillis(),
                "underflow", series.latency().underflow(),
                "overflow", series.latency().overflow(),
                "bucketCounts", series.latency().bucketCounts(),
                "upperBoundsMillis", series.latency().upperBoundsMillis()));
        return rendered;
    }
}
