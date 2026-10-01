package com.github.lechandonga.springobservabilityalertingjava.config;

import com.github.lechandonga.springobservabilityalertingjava.alerting.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.alerting.AlertProperties;
import com.github.lechandonga.springobservabilityalertingjava.metrics.MetricsRegistry;
import com.github.lechandonga.springobservabilityalertingjava.requestid.RequestIdPolicy;
import com.github.lechandonga.springobservabilityalertingjava.requestid.RequestIdProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Instant;

/** 统一装配可观测组件 Bean。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        ObservabilityProperties.class,
        RequestIdProperties.class,
        AlertProperties.class
})
public class ObservabilityConfig {

    @Bean
    public RequestIdPolicy requestIdPolicy() {
        return new RequestIdPolicy();
    }

    @Bean
    public MetricsRegistry metricsRegistry(ObservabilityProperties properties) {
        return new MetricsRegistry(
                properties.getMaxEndpointValues(),
                properties.getMaxStatusValues(),
                properties.getMaxEndpointLength(),
                properties.getLatencyBucketsMillis());
    }

    @Bean
    public AlertEngine alertEngine(AlertProperties alertProperties) {
        AlertEngine engine = new AlertEngine(() -> Instant.now().getEpochSecond());
        engine.replaceRules(alertProperties.getRules().stream()
                .map(AlertProperties.AlertRuleConfig::toRule)
                .toList());
        return engine;
    }
}
