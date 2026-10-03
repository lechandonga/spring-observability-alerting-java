package com.github.lechandonga.springobservabilityalertingjava.alert;

import java.time.Duration;
import java.time.Instant;

/**
 * Read-only runtime view of one rule, explaining the most recent evaluation:
 * the windowed count seen, the decision taken, cooldown/recovery state and
 * the last firing time. Derived from the same state the evaluator uses, so
 * it always matches the evaluation log; reading it never mutates anything.
 */
public record RuleStatus(
        String ruleName,
        Instant lastEvaluatedAt,
        long windowCount,
        long threshold,
        EvaluationRecord.Decision lastDecision,
        boolean inCooldown,
        Duration cooldownRemaining,
        boolean recovered,
        Instant lastFiredAt) {
}
