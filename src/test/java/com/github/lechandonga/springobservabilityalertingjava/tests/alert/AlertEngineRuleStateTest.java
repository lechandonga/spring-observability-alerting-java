package com.github.lechandonga.springobservabilityalertingjava.tests.alert;

import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRule;
import com.github.lechandonga.springobservabilityalertingjava.alert.EvaluationRecord;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertEngineRuleStateTest {

    private static final Logger log = LoggerFactory.getLogger(AlertEngineRuleStateTest.class);

    private MutableClock clock;
    private AlertEngine engine;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-02T00:00:00Z"));
        engine = new AlertEngine(clock, Duration.ofMinutes(10));
    }

    private static AlertRule rule(int threshold, Duration cooldown) {
        return new AlertRule("errors", "GET /api/fail", "5xx", threshold,
                Duration.ofMinutes(1), cooldown);
    }

    private void errors(AlertEngine target, int n) {
        for (int i = 0; i < n; i++) {
            target.recordEvent("GET /api/fail", "5xx", clock.instant());
        }
    }

    @Test
    void redefinedRuleStartsFromCleanState() {
        engine.updateRules(List.of(rule(3, Duration.ofMinutes(5))));
        errors(engine, 3);
        engine.evaluate();
        assertEquals(1, engine.firedAlerts().size(), "original definition fires");

        // Redefine with a huge threshold: old "fired + cooling down" state
        // must be gone, the new definition evaluates from a clean slate.
        engine.updateRules(List.of(rule(100, Duration.ofMinutes(5))));
        RuleStatus status = engine.ruleStatuses().get(0);
        log.info("decision basis: status after redefine={}", status);
        assertNull(status.lastFiredAt(), "redefined rule must forget its last firing");
        assertNull(status.lastDecision(), "redefined rule must forget its last decision");
        assertTrue(status.recovered(), "redefined rule must be armed again");
        assertFalse(status.inCooldown());

        // New definition with threshold 2: the 3 events still inside the
        // window count toward the new definition, so it fires immediately -
        // exactly as if the rule had always had this definition.
        engine.updateRules(List.of(rule(2, Duration.ofMinutes(5))));
        var fired = engine.evaluate();
        log.info("decision basis: fired={} log={}", fired.size(), engine.evaluationLog());
        assertEquals(1, fired.size(), "in-window events must count toward the new definition");
    }

    @Test
    void redefinedRuleMatchesFreshEngineWithSameDefinition() {
        // Engine A: old definition fires, then is redefined mid-stream.
        engine.updateRules(List.of(rule(1, Duration.ofMinutes(5))));
        errors(engine, 1);
        engine.evaluate();
        assertEquals(1, engine.firedAlerts().size());
        engine.updateRules(List.of(rule(3, Duration.ofMinutes(2))));

        // Engine B: starts directly with the new definition, same events,
        // same clock (same time progression).
        AlertEngine fresh = new AlertEngine(clock, Duration.ofMinutes(10));
        fresh.updateRules(List.of(rule(3, Duration.ofMinutes(2))));
        fresh.recordEvent("GET /api/fail", "5xx", clock.instant()); // the event that fired A

        for (int round = 0; round < 6; round++) {
            errors(engine, round % 2 == 0 ? 2 : 0);
            for (int i = 0; i < (round % 2 == 0 ? 2 : 0); i++) {
                fresh.recordEvent("GET /api/fail", "5xx", clock.instant());
            }
            var a = engine.evaluate();
            var b = fresh.evaluate();
            log.info("decision basis: round={} redefined={} fresh={}", round, a, b);
            assertEquals(b, a, "round " + round + ": redefined rule must match a fresh rule");
            clock.advance(Duration.ofSeconds(30));
        }
        assertEquals(fresh.firedAlerts(), engine.firedAlerts().subList(1, engine.firedAlerts().size()),
                "after redefinition the firing history must equal a fresh rule's");
    }

    @Test
    void deletedRuleReleasesStateAndDisappearsFromStatus() {
        engine.updateRules(List.of(rule(3, Duration.ofMinutes(5))));
        errors(engine, 3);
        engine.evaluate();
        assertEquals(1, engine.ruleRuntimeCount());
        assertEquals(1, engine.ruleStatuses().size());

        engine.updateRules(List.of());
        log.info("decision basis: runtimes={} statuses={}",
                engine.ruleRuntimeCount(), engine.ruleStatuses());
        assertEquals(0, engine.ruleRuntimeCount(), "deleted rule must release its runtime state");
        assertTrue(engine.ruleStatuses().isEmpty(), "deleted rule must vanish from the status view");

        // Re-adding the same name starts clean: it can fire immediately,
        // no leftover cooldown from the deleted incarnation.
        engine.updateRules(List.of(rule(3, Duration.ofMinutes(5))));
        var fired = engine.evaluate();
        log.info("decision basis: refired={} status={}", fired.size(), engine.ruleStatuses());
        assertEquals(1, fired.size(), "re-added rule must start from a clean state");
    }

    @Test
    void repeatedAddDeleteDoesNotGrowState() {
        for (int i = 0; i < 100; i++) {
            engine.updateRules(List.of(rule(1, Duration.ZERO)));
            errors(engine, 1);
            engine.evaluate();
            engine.updateRules(List.of());
        }
        log.info("decision basis: runtimes={} series={}",
                engine.ruleRuntimeCount(), engine.trackedSeriesCount());
        assertEquals(0, engine.ruleRuntimeCount(), "runtime state must not accumulate");
    }

    @Test
    void statusViewMatchesEvaluationLog() {
        engine.updateRules(List.of(rule(3, Duration.ofMinutes(2))));
        errors(engine, 3);
        engine.evaluate();
        clock.advance(Duration.ofSeconds(30));
        engine.evaluate();

        EvaluationRecord last = engine.evaluationLog().get(engine.evaluationLog().size() - 1);
        RuleStatus status = engine.ruleStatuses().get(0);
        log.info("decision basis: lastRecord={} status={}", last, status);
        assertEquals(last.windowCount(), status.windowCount(), "status count must match the log");
        assertEquals(last.decision(), status.lastDecision(), "status decision must match the log");
        assertEquals(last.evaluatedAt(), status.lastEvaluatedAt());
        assertEquals(EvaluationRecord.Decision.SUPPRESSED_BY_COOLDOWN, status.lastDecision());
        assertTrue(status.inCooldown(), "fired 30s ago with 2m cooldown");
        assertEquals(Duration.ofSeconds(90), status.cooldownRemaining());
        assertFalse(status.recovered(), "count never dropped below threshold since firing");
        assertEquals(engine.firedAlerts().get(0).firedAt(), status.lastFiredAt());

        clock.advance(Duration.ofMinutes(3)); // window empties -> recovery
        engine.evaluate();
        RuleStatus recovered = engine.ruleStatuses().get(0);
        log.info("decision basis: after recovery status={}", recovered);
        assertTrue(recovered.recovered(), "rule must be armed again");
        assertFalse(recovered.inCooldown(), "cooldown must have elapsed");
        assertEquals(EvaluationRecord.Decision.OK, recovered.lastDecision());
    }
}
