package com.github.lechandonga.springobservabilityalertingjava.web;

import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.metrics.RequestMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only views over the metrics registry and the alert engine, for local
 * verification and debugging.
 */
@RestController
public class ObservabilityController {

    private final RequestMetrics metrics;
    private final AlertEngine alertEngine;

    public ObservabilityController(RequestMetrics metrics, AlertEngine alertEngine) {
        this.metrics = metrics;
        this.alertEngine = alertEngine;
    }

    @GetMapping("/observability/metrics")
    public Map<String, Object> metrics() {
        return Map.of(
                "seriesCount", metrics.seriesCount(),
                "overflowedObservations", metrics.overflowedObservations(),
                "invalidDimensionObservations", metrics.invalidDimensionObservations(),
                "series", metrics.snapshot());
    }

    @GetMapping("/observability/alerts")
    public Map<String, Object> alerts() {
        return Map.of(
                "rules", alertEngine.currentRules(),
                "fired", alertEngine.firedAlerts(),
                "evaluations", alertEngine.evaluationLog());
    }
}
