package com.github.lechandonga.springobservabilityalertingjava.alert;

import java.time.Instant;

/**
 * One fired alert. {@code windowCount} is the exact count that crossed the
 * threshold, so the firing decision is reproducible from the evaluation log.
 */
public record AlertEvent(String ruleName, String series, Instant firedAt, long windowCount, long threshold) {
}
