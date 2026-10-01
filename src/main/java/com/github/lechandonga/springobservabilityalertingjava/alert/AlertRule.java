package com.github.lechandonga.springobservabilityalertingjava.alert;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable threshold rule over a sliding window of request events.
 *
 * <p>Semantics: for the series selected by {@code endpoint} + {@code statusClass},
 * if the number of events within the last {@code window} reaches
 * {@code threshold}, the rule fires once and then stays quiet for
 * {@code cooldown}. It can fire again only after the cooldown elapsed AND the
 * windowed count has dropped below the threshold at least once (recovery).
 */
public record AlertRule(
        String name,
        String endpoint,
        String statusClass,
        int threshold,
        Duration window,
        Duration cooldown) {

    public AlertRule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(statusClass, "statusClass");
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1");
        }
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(cooldown, "cooldown");
        if (window.isNegative() || window.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException("window must be > 0 and cooldown >= 0");
        }
    }
}
