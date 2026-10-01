package com.github.lechandonga.springobservabilityalertingjava.alert;

import java.time.Instant;

/**
 * Auditable record of one rule evaluation: inputs (windowed count), the rule
 * snapshot used, and the resulting decision. Re-running the engine over the
 * same event stream yields the same records (deterministic evaluation).
 */
public record EvaluationRecord(
        String ruleName,
        String series,
        Instant evaluatedAt,
        long windowCount,
        long threshold,
        Decision decision) {

    public enum Decision {
        /** Count below threshold; rule is (back to) armed. */
        OK,
        /** Count >= threshold and rule was armed and not cooling down. */
        FIRED,
        /** Count >= threshold but the rule is inside its cooldown. */
        SUPPRESSED_BY_COOLDOWN,
        /** Count >= threshold but rule has not recovered below threshold since last fire. */
        SUPPRESSED_NOT_RECOVERED
    }
}
