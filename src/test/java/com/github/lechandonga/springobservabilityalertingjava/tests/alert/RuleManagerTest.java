package com.github.lechandonga.springobservabilityalertingjava.tests.alert;

import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRule;
import com.github.lechandonga.springobservabilityalertingjava.alert.EvaluationRecord;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleManagementException;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleManager;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleManagerTest {

    private static final Logger log = LoggerFactory.getLogger(RuleManagerTest.class);
    private static final Instant T0 = Instant.parse("2026-10-02T00:00:00Z");

    private MutableClock clock;
    private AlertEngine engine;
    private RuleManager manager;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        engine = new AlertEngine(clock, Duration.ofMinutes(10));
        manager = new RuleManager(engine, 5);
    }

    private static RuleManager.RuleSpec spec(String name, int threshold) {
        return new RuleManager.RuleSpec(name, "GET /api/fail", "5xx", threshold,
                Duration.ofMinutes(1), Duration.ofMinutes(2));
    }

    private void errors(int n) {
        for (int i = 0; i < n; i++) {
            engine.recordEvent("GET /api/fail", "5xx", clock.instant());
        }
    }

    @Test
    void invalidChangesAreRejectedAndLeaveEverythingUntouched() {
        manager.add(spec("errors", 3));
        errors(3);
        engine.evaluate(); // fires -> runtime now holds lastFiredAt + recovered=false
        List<AlertRule> rulesBefore = manager.list();
        List<EvaluationRecord> logBefore = engine.evaluationLog();
        List<RuleStatus> statusBefore = engine.ruleStatuses();

        log.info("input: fired rule, then 4 invalid changes (missing fields, bad threshold, duplicate, unknown)");
        var missing = assertThrows(RuleManagementException.class,
                () -> manager.add(new RuleManager.RuleSpec(null, "GET /a", "5xx", 1,
                        Duration.ofMinutes(1), Duration.ZERO)));
        assertEquals(RuleManagementException.Code.VALIDATION_FAILED, missing.code());
        var badThreshold = assertThrows(RuleManagementException.class,
                () -> manager.update("errors", spec("errors", 0)));
        assertEquals(RuleManagementException.Code.VALIDATION_FAILED, badThreshold.code());
        var duplicate = assertThrows(RuleManagementException.class,
                () -> manager.add(spec("errors", 9)));
        assertEquals(RuleManagementException.Code.DUPLICATE_RULE, duplicate.code());
        var unknown = assertThrows(RuleManagementException.class,
                () -> manager.delete("nope"));
        assertEquals(RuleManagementException.Code.RULE_NOT_FOUND, unknown.code());

        log.info("decision basis: rules={} statuses={} (must equal pre-rejection snapshots)",
                manager.list(), engine.ruleStatuses());
        assertEquals(rulesBefore, manager.list(), "rejected changes must not alter the rule set");
        assertEquals(logBefore, engine.evaluationLog(), "rejected changes must not add evaluations");
        assertEquals(statusBefore, engine.ruleStatuses(), "rejected changes must not alter rule status");

        errors(3);
        var fired = engine.evaluate();
        log.info("decision basis: fired={} lastDecision={}", fired.size(),
                engine.evaluationLog().get(engine.evaluationLog().size() - 1).decision());
        assertEquals(0, fired.size(), "cooldown state must survive rejected changes");
        assertEquals(EvaluationRecord.Decision.SUPPRESSED_BY_COOLDOWN,
                engine.evaluationLog().get(engine.evaluationLog().size() - 1).decision());
    }

    @Test
    void updatedRuleRestartsFromCleanStateLikeFreshDefinition() {
        // Engine A: old definition fires, then the rule is redefined.
        // Engine B: same events, but the new definition was there from the start.
        manager.add(spec("errors", 3));
        errors(3);
        assertEquals(1, engine.evaluate().size(), "old definition must fire");

        RuleManager.RuleSpec newDef = new RuleManager.RuleSpec("errors", "GET /api/fail", "5xx", 2,
                Duration.ofMinutes(1), Duration.ofMinutes(2));
        manager.update("errors", newDef);
        log.info("input: rule fired under threshold=3, redefined to threshold=2 while cooling down");

        MutableClock clockB = new MutableClock(T0);
        AlertEngine fresh = new AlertEngine(clockB, Duration.ofMinutes(10));
        RuleManager freshManager = new RuleManager(fresh, 5);
        freshManager.add(newDef);
        for (int i = 0; i < 3; i++) { // same pre-change events, still inside the window
            fresh.recordEvent("GET /api/fail", "5xx", clockB.instant());
        }

        var firedA = engine.evaluate();
        var firedB = fresh.evaluate();
        log.info("decision basis: redefined fired={} fresh fired={} (must match)", firedA.size(), firedB.size());
        assertEquals(firedB, firedA, "redefined rule must conclude exactly like a fresh definition");
        assertEquals(1, firedA.size(), "old cooldown/recovery memory must not suppress the new definition");

        // Same subsequent traffic and time advance -> identical conclusions.
        for (int round = 0; round < 3; round++) {
            clock.advance(Duration.ofSeconds(40));
            clockB.advance(Duration.ofSeconds(40));
            errors(2);
            for (int i = 0; i < 2; i++) {
                fresh.recordEvent("GET /api/fail", "5xx", clockB.instant());
            }
            assertEquals(fresh.evaluate(), engine.evaluate(),
                    "round " + round + ": conclusions must stay identical to the fresh engine");
        }
        log.info("decision basis: redefined log tail={} fresh log={}",
                engine.evaluationLog().subList(2, engine.evaluationLog().size()), fresh.evaluationLog());
    }

    @Test
    void deleteReleasesStateAndRepeatedChurnDoesNotGrow() {
        manager.add(spec("errors", 3));
        errors(3);
        engine.evaluate();
        assertEquals(1, engine.trackedRuleStateCount());
        assertEquals(1, engine.ruleStatuses().size());

        manager.delete("errors");
        log.info("input: deleted fired rule; decision basis: ruleStates={} statuses={}",
                engine.trackedRuleStateCount(), engine.ruleStatuses().size());
        assertEquals(0, engine.trackedRuleStateCount(), "deleted rule must release its runtime state");
        assertEquals(0, engine.ruleStatuses().size(), "deleted rule must disappear from the status view");

        for (int i = 0; i < 50; i++) {
            manager.add(spec("churn-" + i, 1));
            engine.evaluate();
            manager.delete("churn-" + i);
        }
        log.info("decision basis: after 50 add/delete cycles ruleStates={}", engine.trackedRuleStateCount());
        assertEquals(0, engine.trackedRuleStateCount(), "repeated add/delete must not accumulate state");
    }

    @Test
    void statusViewMatchesEvaluationLog() {
        manager.add(spec("errors", 3));
        errors(3);
        engine.evaluate(); // FIRED
        clock.advance(Duration.ofSeconds(30));
        errors(1);
        engine.evaluate(); // SUPPRESSED_BY_COOLDOWN

        RuleStatus status = engine.ruleStatuses().get(0);
        EvaluationRecord last = engine.evaluationLog().get(engine.evaluationLog().size() - 1);
        log.info("input: fired then 1 more error 30s later; decision basis: status={} lastRecord={}", status, last);
        assertEquals(last.ruleName(), status.ruleName());
        assertEquals(last.windowCount(), status.windowCount(), "status count must match the evaluation record");
        assertEquals(last.decision(), status.lastDecision(), "status decision must match the evaluation record");
        assertEquals(last.evaluatedAt(), status.evaluatedAt());
        assertTrue(status.inCooldown(), "30s into a 2m cooldown must still be cooling down");
        assertEquals(Duration.ofSeconds(90), status.cooldownRemaining());
        assertEquals(T0, status.lastFiredAt());
        assertTrue(!status.recovered(), "count never dropped below threshold since firing");

        clock.advance(Duration.ofMinutes(3)); // window empties, cooldown elapses
        engine.evaluate();
        RuleStatus recovered = engine.ruleStatuses().get(0);
        log.info("decision basis: after window drained status={}", recovered);
        assertTrue(recovered.recovered(), "rule must re-arm once the count drops below threshold");
        assertTrue(!recovered.inCooldown());
        assertEquals(EvaluationRecord.Decision.OK, recovered.lastDecision());
    }

    @Test
    void limitIsHardUnderConcurrentAdds() throws InterruptedException {
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<RuleManagementException> rejections = new ConcurrentLinkedQueue<>();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String name = "rule-" + i;
            Thread t = new Thread(() -> {
                await(start);
                try {
                    manager.add(spec(name, 1));
                } catch (RuleManagementException ex) {
                    rejections.add(ex);
                }
            });
            workers.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : workers) {
            t.join();
        }
        log.info("input: {} concurrent adds with maxRules=5; decision basis: accepted={} rejected={}",
                threads, manager.list().size(), rejections.size());
        assertEquals(5, manager.list().size(), "the limit must hold under concurrency");
        assertEquals(threads - 5, rejections.size());
        assertTrue(rejections.stream().allMatch(
                ex -> ex.code() == RuleManagementException.Code.RULE_LIMIT_EXCEEDED));
    }

    @Test
    void concurrentMutationsAndEvaluationStayConsistent() throws InterruptedException {
        manager.add(spec("base", 2));
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int id = i;
            Thread t = new Thread(() -> {
                await(start);
                try {
                    for (int round = 0; round < 30; round++) {
                        String name = "w" + id + "-" + (round % 3);
                        try {
                            manager.add(spec(name, 2));
                        } catch (RuleManagementException ignored) {
                            // limit or duplicate: both fine, state must stay consistent
                        }
                        try {
                            manager.update(name, spec(name, 3));
                        } catch (RuleManagementException ignored) {
                        }
                        try {
                            manager.delete(name);
                        } catch (RuleManagementException ignored) {
                        }
                    }
                } catch (Throwable ex) {
                    failures.add(ex);
                }
            });
            workers.add(t);
            t.start();
        }
        Thread evaluator = new Thread(() -> {
            await(start);
            try {
                for (int round = 0; round < 200; round++) {
                    errors(1);
                    engine.evaluate();
                }
            } catch (Throwable ex) {
                failures.add(ex);
            }
        });
        workers.add(evaluator);
        evaluator.start();
        start.countDown();
        for (Thread t : workers) {
            t.join();
        }

        Map<String, AlertRule> live = manager.list().stream()
                .collect(Collectors.toMap(AlertRule::name, Function.identity()));
        log.info("decision basis: failures={} liveRules={} statuses={} ruleStates={}",
                failures.size(), live.keySet(),
                engine.ruleStatuses().stream().map(RuleStatus::ruleName).toList(),
                engine.trackedRuleStateCount());
        assertTrue(failures.isEmpty(), "no concurrent operation may fail unexpectedly: " + failures);
        assertTrue(manager.list().size() <= 5, "limit must never be exceeded");
        for (RuleStatus status : engine.ruleStatuses()) {
            assertTrue(live.containsKey(status.ruleName()),
                    "status view must not contain rules that are gone: " + status.ruleName());
        }
        assertTrue(engine.trackedRuleStateCount() <= live.size(),
                "runtime state must never exceed the live rules (no leaks)");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
