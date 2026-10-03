package com.github.lechandonga.springobservabilityalertingjava.config;

import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRule;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleManager;
import com.github.lechandonga.springobservabilityalertingjava.metrics.RequestMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.List;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RequestMetrics requestMetrics(ObservabilityProperties props) {
        return new RequestMetrics(props.getMaxSeries());
    }

    @Bean
    public AlertEngine alertEngine(Clock clock, ObservabilityProperties props) {
        AlertEngine engine = new AlertEngine(clock, props.getIdleSeriesTtl());
        List<AlertRule> rules = props.getRules().stream()
                .map(r -> new AlertRule(r.getName(), r.getEndpoint(), r.getStatusClass(),
                        r.getThreshold(), r.getWindow(), r.getCooldown()))
                .toList();
        engine.updateRules(rules);
        return engine;
    }

    @Bean
    public RuleManager ruleManager(AlertEngine alertEngine, ObservabilityProperties props) {
        return new RuleManager(alertEngine, props.getMaxRules());
    }
}
