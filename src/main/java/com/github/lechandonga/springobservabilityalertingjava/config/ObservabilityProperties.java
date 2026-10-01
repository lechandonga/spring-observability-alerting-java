package com.github.lechandonga.springobservabilityalertingjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the observability pipeline, prefix {@code observability}.
 */
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    /** Hard cap on distinct metric series (endpoint + statusClass). */
    private int maxSeries = 500;

    /** How long a series may go without events before it is evicted. */
    private Duration idleSeriesTtl = Duration.ofMinutes(30);

    /** How often alert rules are evaluated. */
    private Duration evaluationInterval = Duration.ofSeconds(10);

    /** Alert rules, evaluated atomically as one set. */
    private List<Rule> rules = new ArrayList<>();

    public int getMaxSeries() {
        return maxSeries;
    }

    public void setMaxSeries(int maxSeries) {
        this.maxSeries = maxSeries;
    }

    public Duration getIdleSeriesTtl() {
        return idleSeriesTtl;
    }

    public void setIdleSeriesTtl(Duration idleSeriesTtl) {
        this.idleSeriesTtl = idleSeriesTtl;
    }

    public Duration getEvaluationInterval() {
        return evaluationInterval;
    }

    public void setEvaluationInterval(Duration evaluationInterval) {
        this.evaluationInterval = evaluationInterval;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    public static class Rule {
        private String name;
        private String endpoint;
        private String statusClass;
        private int threshold;
        private Duration window;
        private Duration cooldown;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getStatusClass() {
            return statusClass;
        }

        public void setStatusClass(String statusClass) {
            this.statusClass = statusClass;
        }

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public Duration getCooldown() {
            return cooldown;
        }

        public void setCooldown(Duration cooldown) {
            this.cooldown = cooldown;
        }
    }
}
