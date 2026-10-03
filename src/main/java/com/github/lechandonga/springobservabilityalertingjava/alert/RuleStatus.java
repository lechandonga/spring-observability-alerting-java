package com.github.lechandonga.springobservabilityalertingjava.alert;

import java.time.Duration;
import java.time.Instant;

/**
 * Point-in-time view of one rule's runtime state, captured during the last
 * evaluation pass. Explains "why did this rule not fire": the exact windowed
 * count, whether the cooldown is still running (and for how long), whether
 * the rule has recovered and can fire again, when it last fired, and the most
 * recent decision (consistent with the {@link EvaluationRecord} audit log).
 */
public record RuleStatus(
        String ruleName,
        String series,
        Instant evaluatedAt,
        long windowCount,
        long threshold,
        boolean inCooldown,
        Duration cooldownRemaining,
        boolean recovered,
        Instant lastFiredAt,
        EvaluationRecord.Decision lastDecision) {
}
