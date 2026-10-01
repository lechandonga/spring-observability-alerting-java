package com.github.lechandonga.springobservabilityalertingjava.tests.alert;

import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRule;
import com.github.lechandonga.springobservabilityalertingjava.alert.EvaluationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertEngineTest {

    private static final Logger log = LoggerFactory.getLogger(AlertEngineTest.class);

    private MutableClock clock;
    private AlertEngine engine;
    private AlertRule rule;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-02T00:00:00Z"));
        engine = new AlertEngine(clock, Duration.ofMinutes(10));
        rule = new AlertRule("errors", "GET /api/fail", "5xx", 3,
                Duration.ofMinutes(1), Duration.ofMinutes(2));
        engine.updateRules(List.of(rule));
    }

    private void errors(int n) {
        for (int i = 0; i < n; i++) {
            engine.recordEvent("GET /api/fail", "5xx", clock.instant());
        }
    }

    @Test
    void cooldownSuppressesRepeatAndRecoveryRearms() {
        log.info("input: threshold=3, window=1m, cooldown=2m; fire 3 errors");
        errors(3);
        var first = engine.evaluate();
        log.info("decision basis: fired={} log={}", first.size(), engine.evaluationLog());
        assertEquals(1, first.size(), "threshold reached must fire exactly once");

        errors(3);
        var second = engine.evaluate();
        assertEquals(0, second.size(), "cooldown must suppress repeat");
        assertEquals(EvaluationRecord.Decision.SUPPRESSED_BY_COOLDOWN,
                engine.evaluationLog().get(1).decision());

        clock.advance(Duration.ofMinutes(3)); // window empties -> recovery
        var third = engine.evaluate();
        assertEquals(0, third.size());
        assertEquals(EvaluationRecord.Decision.OK, engine.evaluationLog().get(2).decision());

        errors(3); // cooldown elapsed and rule recovered -> fires again
        var fourth = engine.evaluate();
        log.info("decision basis: refired={} totalFired={}", fourth.size(), engine.firedAlerts().size());
        assertEquals(1, fourth.size(), "after recovery + cooldown the rule must fire again");
        assertEquals(2, engine.firedAlerts().size());
    }

    @Test
    void noRepeatWhileCountStaysAboveThreshold() {
        // window 5m > cooldown 1m: after cooldown the count is still hot and
        // the rule never dropped below threshold, so it must NOT fire again.
        AlertRule longWindow = new AlertRule("errors", "GET /api/fail", "5xx", 3,
                Duration.ofMinutes(5), Duration.ofMinutes(1));
        engine.updateRules(List.of(longWindow));
        errors(3);
        engine.evaluate();
        clock.advance(Duration.ofMinutes(2)); // cooldown over, events still in window
        var fired = engine.evaluate();
        log.info("decision basis: fired={} lastDecision={}", fired.size(),
                engine.evaluationLog().get(engine.evaluationLog().size() - 1).decision());
        assertEquals(0, fired.size(), "must stay suppressed until count recovers below threshold");
        assertEquals(EvaluationRecord.Decision.SUPPRESSED_NOT_RECOVERED,
                engine.evaluationLog().get(engine.evaluationLog().size() - 1).decision());
    }

    @Test
    void ruleUpdateIsAtomicForEvaluation() {
        errors(2);
        AlertRule stricter = new AlertRule("errors", "GET /api/fail", "5xx", 1,
                Duration.ofMinutes(1), Duration.ZERO);
        engine.updateRules(List.of(stricter));
        var fired = engine.evaluate();
        log.info("decision basis: rules={} fired={}", engine.currentRules(), fired.size());
        assertEquals(List.of(stricter), engine.currentRules(), "evaluation must see the whole new rule set");
        assertEquals(1, fired.size());
    }

    @Test
    void idleSeriesAreEvicted() {
        engine.recordEvent("GET /old", "2xx", clock.instant());
        assertEquals(1, engine.trackedSeriesCount());
        clock.advance(Duration.ofMinutes(11));
        engine.evaluate();
        log.info("decision basis: idleTtl=10m, trackedSeries={}", engine.trackedSeriesCount());
        assertEquals(0, engine.trackedSeriesCount(), "idle series must be reclaimed");
    }

    @Test
    void evaluationIsReproducible() {
        List<EvaluationRecord> first = runScript();
        List<EvaluationRecord> second = runScript();
        log.info("decision basis: run1={} run2={}", first, second);
        assertEquals(first, second, "same events + rules must yield identical conclusions");
    }

    private List<EvaluationRecord> runScript() {
        MutableClock c = new MutableClock(Instant.parse("2026-10-02T00:00:00Z"));
        AlertEngine e = new AlertEngine(c, Duration.ofMinutes(10));
        e.updateRules(List.of(rule));
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < round + 1; i++) {
                e.recordEvent("GET /api/fail", "5xx", c.instant());
            }
            e.evaluate();
            c.advance(Duration.ofSeconds(30));
        }
        return e.evaluationLog();
    }

    @Test
    void slidingWindowDropsExpiredEvents() {
        errors(2);
        clock.advance(Duration.ofSeconds(90)); // beyond 1m window
        errors(2);
        var fired = engine.evaluate();
        log.info("decision basis: windowCount={} fired={}",
                engine.evaluationLog().get(0).windowCount(), fired.size());
        assertEquals(0, fired.size(), "expired events must not count");
        assertEquals(2, engine.evaluationLog().get(0).windowCount());
        assertTrue(engine.firedAlerts().isEmpty());
    }
}
