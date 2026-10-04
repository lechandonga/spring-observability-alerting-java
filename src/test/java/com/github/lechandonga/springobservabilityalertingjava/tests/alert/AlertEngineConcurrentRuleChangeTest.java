package com.github.lechandonga.springobservabilityalertingjava.tests.alert;

import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRule;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRuleManager;
import com.github.lechandonga.springobservabilityalertingjava.alert.EvaluationRecord;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleDefinition;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleStatus;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives rule changes (redefine / delete / re-add / rejected input) from one
 * thread while a second thread evaluates continuously, then asserts the
 * committed-change semantics hold despite the interleaving: redefined rules
 * forget their old trigger/cooldown/recovery memory, deleted rules are never
 * resurrected by an in-flight evaluation, and rejected changes are no-ops.
 */
class AlertEngineConcurrentRuleChangeTest {

    private static final Logger log = LoggerFactory.getLogger(AlertEngineConcurrentRuleChangeTest.class);

    private static final int ROUNDS = 100;

    private MutableClock clock;
    private AlertEngine engine;
    private AlertRuleManager manager;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-02T00:00:00Z"));
        engine = new AlertEngine(clock, Duration.ofMinutes(10));
        manager = new AlertRuleManager(engine, 10);
    }

    private static RuleDefinition def(int threshold, Duration cooldown) {
        return new RuleDefinition("errors", "GET /api/fail", "5xx", threshold,
                Duration.ofMinutes(1), cooldown);
    }

    private void errors(AlertEngine target, int n) {
        for (int i = 0; i < n; i++) {
            target.recordEvent("GET /api/fail", "5xx", clock.instant());
        }
    }

    /** Runs {@code engine.evaluate()} in a loop until {@code running} flips. */
    private Thread startEvaluator(AtomicBoolean running) {
        Thread evaluator = new Thread(() -> {
            while (running.get()) {
                engine.evaluate();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "test-evaluator");
        evaluator.start();
        return evaluator;
    }

    private static void stopEvaluator(AtomicBoolean running, Thread evaluator) throws InterruptedException {
        running.set(false);
        evaluator.join();
    }

    /**
     * Arms the engine's evaluation hook so the next evaluation pass blocks
     * after taking its rule snapshot, letting the caller commit a rule change
     * exactly mid-pass. Returns latches: [snapshotTaken, releasePass].
     */
    private CountDownLatch[] pauseNextEvaluationMidPass() {
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch releasePass = new CountDownLatch(1);
        engine.evaluationHook = () -> {
            snapshotTaken.countDown();
            try {
                releasePass.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        return new CountDownLatch[]{snapshotTaken, releasePass};
    }

    @Test
    void deleteCommittedMidEvaluationIsNotResurrected() throws Exception {
        manager.add(def(1, Duration.ZERO));
        errors(engine, 1);
        engine.evaluate();
        assertEquals(1, engine.firedAlerts().size(), "rule fires before the delete");
        assertEquals(1, engine.ruleRuntimeCount());

        // An evaluation pass starts and blocks right after taking its rule
        // snapshot (which still contains the rule); the delete commits while
        // the pass is parked.
        CountDownLatch[] latches = pauseNextEvaluationMidPass();
        Thread pass = new Thread(engine::evaluate, "test-evaluate-pass");
        pass.start();
        assertTrue(latches[0].await(5, TimeUnit.SECONDS), "evaluation pass must reach the snapshot point");

        manager.delete("errors");
        log.info("decision basis: delete committed mid-pass, runtimes={}", engine.ruleRuntimeCount());
        assertEquals(0, engine.ruleRuntimeCount(), "delete must release the rule's state immediately");
        latches[1].countDown();
        pass.join();
        engine.evaluationHook = () -> { };

        int alertsAfterDelete = engine.firedAlerts().size();
        int logAfterDelete = engine.evaluationLog().size();
        log.info("decision basis: after mid-pass delete runtimes={} statuses={} alerts={}",
                engine.ruleRuntimeCount(), engine.ruleStatuses(), alertsAfterDelete);
        assertEquals(0, engine.ruleRuntimeCount(),
                "the in-flight pass must not resurrect the deleted rule's state");
        assertTrue(engine.ruleStatuses().isEmpty(), "deleted rule must not reappear in the status view");

        // Subsequent passes stay silent: no conclusions, no alerts, no state.
        var fired = engine.evaluate();
        assertTrue(fired.isEmpty());
        assertEquals(alertsAfterDelete, engine.firedAlerts().size(), "deleted rule must not alert");
        assertEquals(logAfterDelete, engine.evaluationLog().size(), "deleted rule must not be evaluated");
        assertEquals(0, engine.ruleRuntimeCount(), "deleted rule's state must stay released");
    }

    @Test
    void redefineCommittedMidEvaluationForgetsOldState() throws Exception {
        // Old definition fires and is now cooling down (5-minute cooldown).
        manager.add(def(3, Duration.ofMinutes(5)));
        errors(engine, 3);
        engine.evaluate();
        assertEquals(1, engine.firedAlerts().size());
        assertTrue(engine.ruleStatuses().get(0).inCooldown());

        // The next evaluation pass parks after its snapshot; the redefinition
        // (easier threshold, long cooldown) commits while it is parked.
        CountDownLatch[] latches = pauseNextEvaluationMidPass();
        Thread pass = new Thread(engine::evaluate, "test-evaluate-pass");
        pass.start();
        assertTrue(latches[0].await(5, TimeUnit.SECONDS), "evaluation pass must reach the snapshot point");

        manager.update("errors", def(2, Duration.ofMinutes(5)));
        log.info("decision basis: redefine committed mid-pass, status={}", engine.ruleStatuses());
        latches[1].countDown();
        pass.join();
        engine.evaluationHook = () -> { };

        // The in-flight pass must not leak the old definition's trigger into
        // the new definition's runtime: the next pass sees count 3 >= new
        // threshold 2 and must fire, exactly like a fresh engine that only
        // ever knew the new definition. No alert from the old definition may
        // appear after the redefinition committed.
        int alertsAfterRedefine = engine.firedAlerts().size();
        assertEquals(1, alertsAfterRedefine, "old definition must not fire after the redefinition committed");

        AlertEngine fresh = new AlertEngine(clock, Duration.ofMinutes(10));
        fresh.updateRules(List.of(new AlertRule("errors", "GET /api/fail", "5xx", 2,
                Duration.ofMinutes(1), Duration.ofMinutes(5))));
        errors(fresh, 3); // same live window contents

        var firedNow = engine.evaluate();
        var freshFired = fresh.evaluate();
        EvaluationRecord record = engine.evaluationLog().get(engine.evaluationLog().size() - 1);
        log.info("decision basis: first-after-mid-pass-redefine fired={} record={} status={}",
                firedNow, record, engine.ruleStatuses());
        assertEquals(1, firedNow.size(),
                "satisfied new threshold must fire; old cooldown/recovery must not suppress it");
        assertEquals(EvaluationRecord.Decision.FIRED, record.decision());
        assertEquals(freshFired, firedNow, "must match a fresh engine's first conclusion");

        for (int round = 0; round < 4; round++) {
            clock.advance(Duration.ofSeconds(20));
            int newEvents = round == 1 ? 1 : 0;
            errors(engine, newEvents);
            errors(fresh, newEvents);
            var a = engine.evaluate();
            var b = fresh.evaluate();
            log.info("decision basis: round={} newEvents={} redefined={} fresh={}", round, newEvents, a, b);
            assertEquals(b, a, "round " + round + ": redefined rule must match a fresh rule");
        }
    }

    @Test
    void concurrentRedefineDeleteReaddThenDecisionsMatchFreshEngine() throws Exception {
        // Old definition fires and is now inside its 5-minute cooldown.
        manager.add(def(3, Duration.ofMinutes(5)));
        errors(engine, 3);
        engine.evaluate();
        assertEquals(1, engine.firedAlerts().size(), "old definition fires");
        assertTrue(engine.ruleStatuses().get(0).inCooldown(), "old definition is cooling down");

        // While the evaluator spins, hammer the rule with redefines, deletes,
        // re-adds of the same name, and invalid input that must be rejected.
        AtomicBoolean running = new AtomicBoolean(true);
        Thread evaluator = startEvaluator(running);
        for (int round = 0; round < ROUNDS; round++) {
            manager.update("errors", def(100, Duration.ZERO));
            manager.delete("errors");
            manager.add(def(100, Duration.ZERO));
            RuleDefinition invalid = new RuleDefinition("errors", "GET /api/fail", "5xx",
                    0, Duration.ofMinutes(1), Duration.ZERO);
            assertThrows(RuleValidationException.class, () -> manager.update("errors", invalid),
                    "invalid change must be rejected even under concurrency");
            if (round % 20 == 0) {
                log.info("decision basis: stress round={} runtimes={} statuses={}",
                        round, engine.ruleRuntimeCount(), engine.ruleStatuses());
            }
        }
        stopEvaluator(running, evaluator);

        // The rejected changes were no-ops: exactly the re-added rule survives.
        assertEquals(List.of("errors"), engine.currentRules().stream().map(AlertRule::name).toList());
        assertEquals(100, engine.currentRules().get(0).threshold());
        engine.evaluate(); // materialize the runtime of the surviving rule
        assertEquals(1, engine.ruleRuntimeCount(), "add/delete churn must not leak runtime state");
        RuleStatus status = engine.ruleStatuses().get(0);
        log.info("decision basis: after stress status={}", status);
        assertEquals(100, status.threshold(), "status view must show the current definition");
        assertFalse(status.inCooldown(), "new definition has zero cooldown, no stale cooldown allowed");

        // Commit the final definition. From here on, decisions must equal a
        // fresh engine that only ever knew this definition, round by round.
        manager.update("errors", def(2, Duration.ofSeconds(30)));
        AlertEngine fresh = new AlertEngine(clock, Duration.ofMinutes(10));
        fresh.updateRules(List.of(new AlertRule("errors", "GET /api/fail", "5xx", 2,
                Duration.ofMinutes(1), Duration.ofSeconds(30))));
        // Same live window contents: the 3 events from before the redefinition.
        errors(fresh, 3);

        // First conclusion of the new definition: the in-window events already
        // satisfy the new threshold, so it must fire - the old definition's
        // cooldown / not-recovered memory must not suppress it.
        var firstFired = engine.evaluate();
        var freshFirstFired = fresh.evaluate();
        EvaluationRecord firstRecord = engine.evaluationLog().get(engine.evaluationLog().size() - 1);
        log.info("decision basis: first-after-redefine fired={} record={}", firstFired, firstRecord);
        assertEquals(1, firstFired.size(), "satisfied new threshold must fire, not be suppressed");
        assertEquals(EvaluationRecord.Decision.FIRED, firstRecord.decision());
        assertEquals(freshFirstFired, firstFired, "must match a fresh engine's first conclusion");

        for (int round = 0; round < 6; round++) {
            clock.advance(Duration.ofSeconds(10));
            int newEvents = round % 2 == 0 ? 2 : 0;
            errors(engine, newEvents);
            errors(fresh, newEvents);
            var a = engine.evaluate();
            var b = fresh.evaluate();
            log.info("decision basis: round={} newEvents={} redefined={} fresh={}", round, newEvents, a, b);
            assertEquals(b, a, "round " + round + ": redefined rule must match a fresh rule");
        }
        assertEquals(fresh.firedAlerts(),
                engine.firedAlerts().subList(engine.firedAlerts().size() - fresh.firedAlerts().size(),
                        engine.firedAlerts().size()),
                "post-redefinition firing history must equal a fresh rule's");
    }

    @Test
    void concurrentDeleteIsNotResurrectedByInFlightEvaluation() throws Exception {
        manager.add(def(1, Duration.ZERO));
        errors(engine, 1);
        engine.evaluate();
        assertEquals(1, engine.firedAlerts().size());

        // Evaluator spins while the rule is deleted and re-added over and
        // over; the loop ends with a committed delete.
        AtomicBoolean running = new AtomicBoolean(true);
        Thread evaluator = startEvaluator(running);
        for (int round = 0; round < ROUNDS; round++) {
            manager.delete("errors");
            manager.add(def(1, Duration.ZERO));
            manager.delete("errors");
            manager.add(def(1, Duration.ZERO));
            if (round % 20 == 0) {
                log.info("decision basis: delete-churn round={} runtimes={}",
                        round, engine.ruleRuntimeCount());
            }
        }
        manager.delete("errors");
        stopEvaluator(running, evaluator);

        int alertsAfterDelete = engine.firedAlerts().size();
        int logAfterDelete = engine.evaluationLog().size();
        log.info("decision basis: after final delete runtimes={} statuses={} alerts={}",
                engine.ruleRuntimeCount(), engine.ruleStatuses(), alertsAfterDelete);
        assertTrue(engine.currentRules().isEmpty(), "rule must be gone");
        assertEquals(0, engine.ruleRuntimeCount(),
                "deleted rule's state must be released and never resurrected");
        assertTrue(engine.ruleStatuses().isEmpty(), "deleted rule must vanish from the status view");

        // A further evaluation pass produces nothing for the deleted rule.
        var fired = engine.evaluate();
        assertTrue(fired.isEmpty(), "deleted rule must not fire");
        assertEquals(alertsAfterDelete, engine.firedAlerts().size(), "no new alerts after delete");
        assertEquals(logAfterDelete, engine.evaluationLog().size(), "no new conclusions after delete");
        assertEquals(0, engine.ruleRuntimeCount(), "evaluation must not re-create deleted state");

        // Rebuilding the same name starts from a clean state and fires at once.
        manager.add(def(1, Duration.ZERO));
        var refired = engine.evaluate();
        log.info("decision basis: rebuilt rule first decision={} status={}",
                refired, engine.ruleStatuses());
        assertEquals(1, refired.size(), "rebuilt rule must start clean and fire");
        assertEquals(1, engine.ruleRuntimeCount(), "only the rebuilt rule's state exists");
    }
}
