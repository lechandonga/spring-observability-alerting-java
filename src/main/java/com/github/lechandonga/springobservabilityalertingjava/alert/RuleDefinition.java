package com.github.lechandonga.springobservabilityalertingjava.alert;

import java.time.Duration;

/**
 * Raw, possibly invalid rule input (e.g. from the REST API). Uses a boxed
 * {@code Integer} threshold so a missing value is distinguishable from 0.
 * Validated by {@link AlertRuleManager} before it becomes an {@link AlertRule}.
 */
public record RuleDefinition(
        String name,
        String endpoint,
        String statusClass,
        Integer threshold,
        Duration window,
        Duration cooldown) {
}
