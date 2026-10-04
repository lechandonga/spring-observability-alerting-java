package com.github.lechandonga.springobservabilityalertingjava.tests.alert;

import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEvent;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRule;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRuleManager;
import com.github.lechandonga.springobservabilityalertingjava.alert.EvaluationRecord;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleDefinition;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleStatus;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleValidationException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces evaluation-vs-rule-change interleavings under sustained
 * concurrent load: evaluator threads spin {@link AlertEngine#evaluate()}
 * while a mutator redefines or deletes the target rule, invalid changes are
 * submitted in parallel, and a sampler reads the status view.
 *
 * <p>Each pass is deliberately wide (many filler rules per pass) so the race
 * windows of a non-serialized implementation (snapshot swap before runtime
 * cleanup; deleted runtime re-created by an in-flight evaluation) are hit
 * reliably. The test fails on the old behavior and stays green once rule
 * changes and evaluations are serialized inside the engine.
 */
class AlertEngineConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(AlertEngineConcurrencyTest.class);

    private static final String RULE = "errors";
    private static final String ENDPOINT = "GET /api/fail";
    private static final String KEEPER = "keeper-rule";
    private static final int FILLERS = 8;
    private static final int EVALUATORS = 4;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration COOLDOWN = Duration.ofMinutes(5);
    private static final Duration IDLE_TTL = Duration.ofMinutes(10);

    private static AlertRule definition(int threshold) {
        return new AlertRule(RULE, ENDPOINT, "5xx", threshold, WINDOW, COOLDOWN);
    }

    private static RuleDefinition definitionDto(int threshold) {
        return new RuleDefinition(RULE, ENDPOINT, "5xx", threshold, WINDOW, COOLDOWN);
    }

    private static List<AlertRule> baseRules(boolean withTarget) {
        List<AlertRule> rules = new ArrayList<>();
        rules.add(new AlertRule(KEEPER, "GET /api/keep", "5xx", 50, WINDOW, COOLDOWN));
        for (int i = 0; i < FILLERS; i++) {
            rules.add(new AlertRule("filler-" + i, "GET /api/filler-" + i, "5xx",
                    50, WINDOW, COOLDOWN));
        }
        if (withTarget) {
            rules.add(definition(3));
        }
        return rules;
    }

    private static void ingest(AlertEngine engine, Instant at, int n) {
        for (int i = 0; i < n; i++) {
            engine.recordEvent(ENDPOINT, "5xx", at);
        }
    }

    private static List<EvaluationRecord> recordsForRule(AlertEngine engine) {
        return engine.evaluationLog().stream()
                .filter(r -> r.ruleName().equals(RULE))
                .toList();
    }

    private static List<AlertEvent> alertsForRule(AlertEngine engine) {
        return engine.firedAlerts().stream()
                .filter(a -> a.ruleName().equals(RULE))
                .toList();
    }

    private static void expectRejected(Runnable op) {
        assertThrows(RuleValidationException.class, op::run);
    }

    @Test
    void concurrentRedefineDeleteAndRecreateStayConsistentWithRunningEvaluations() throws Exception {
        Instant roundStart = Instant.parse("2026-10-02T00:00:00Z");
        for (int round = 0; round < 12; round++) {
            // A gap longer than window + idle TTL empties per-series event
            // data between rounds, so every round is an independent scenario.
            roundStart = roundStart.plus(IDLE_TTL).plus(Duration.ofMinutes(2));
            MutableClock clock = new MutableClock(roundStart);
            AlertEngine engine = new AlertEngine(clock, IDLE_TTL);
            AlertRuleManager manager = new AlertRuleManager(engine, 20);
            if (round % 2 == 0) {
                runRedefineRound(round, clock, engine, manager);
            } else {
                runDeleteRound(round, clock, engine, manager);
            }
        }
    }

    private void runRedefineRound(int round, MutableClock clock, AlertEngine engine,
                                  AlertRuleManager manager) throws Exception {
        Instant t = clock.instant();
        engine.updateRules(baseRules(true));
        ingest(engine, t, 3);
        engine.evaluate();
        assertEquals(EvaluationRecord.Decision.FIRED, recordsForRule(engine).get(0).decision(),
                "old definition fires first");
        ingest(engine, t, 2); // window count now 5 >= new threshold 2

        int alertsBefore = alertsForRule(engine).size();
        List<Instant> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(t);
        }

        SpinResult spin = runSpin(engine, manager,
                () -> manager.update(RULE, definitionDto(2)));

        List<EvaluationRecord> newDefAtT = recordsForRule(engine).stream()
                .filter(r -> r.threshold() == 2 && r.evaluatedAt().equals(t))
                .toList();
        long firedAtT = newDefAtT.stream()
                .filter(r -> r.decision() == EvaluationRecord.Decision.FIRED).count();
        long notRecoveredAtT = newDefAtT.stream()
                .filter(r -> r.decision() == EvaluationRecord.Decision.SUPPRESSED_NOT_RECOVERED)
                .count();
        log.info("round={} scenario=REDEFINE input={{oldThreshold=3,newThreshold=2,events=5 at {}}} "
                        + "decision basis: newDefRecords={} fired={} notRecoveredSuppressions={}",
                round, t, newDefAtT, firedAtT, notRecoveredAtT);
        assertFalse(newDefAtT.isEmpty(), "round " + round + ": new definition must be evaluated");
        assertEquals(EvaluationRecord.Decision.FIRED, newDefAtT.get(0).decision(),
                "round " + round + ": first post-commit decision under the new definition must be "
                        + "FIRED; old cooldown/not-recovered memory must be gone: " + newDefAtT);
        assertEquals(1, firedAtT, "round " + round + ": exactly one firing at instant " + t);
        assertEquals(0, notRecoveredAtT,
                "round " + round + ": new definition must never carry NOT_RECOVERED memory");
        assertEquals(alertsBefore + 1, alertsForRule(engine).size(),
                "round " + round + ": exactly one alert from the new definition");

        RuleStatus status = engine.ruleStatuses().stream()
                .filter(s -> s.ruleName().equals(RULE)).findFirst().orElseThrow();
        assertEquals(2, status.threshold(), "status view must show the NEW definition");
        assertTrue(status.inCooldown(), "fired under the new definition at this instant");
        assertEquals(alertsForRule(engine).get(alertsForRule(engine).size() - 1).firedAt(),
                status.lastFiredAt());
        assertEquals(FILLERS + 2, engine.ruleRuntimeCount());
        spin.assertConsistent(round);

        assertDecisionsMatchFreshEngine(round, clock, engine, events);
    }

    private void runDeleteRound(int round, MutableClock clock, AlertEngine engine,
                                AlertRuleManager manager) throws Exception {
        Instant t = clock.instant();
        engine.updateRules(baseRules(true));
        ingest(engine, t, 3);
        engine.evaluate();
        assertEquals(1, alertsForRule(engine).size());

        SpinResult spin = runSpin(engine, manager, () -> manager.delete(RULE));

        long logSizeAfterSpin = engine.evaluationLog().size();
        int alertsAfterSpin = engine.firedAlerts().size();
        engine.evaluate(); // the deleted rule must stay silent
        List<EvaluationRecord> postDeleteRecords = engine.evaluationLog()
                .subList((int) logSizeAfterSpin, engine.evaluationLog().size());
        assertTrue(postDeleteRecords.stream().noneMatch(r -> r.ruleName().equals(RULE)),
                "round " + round + ": deleted rule must produce no records after commit");
        assertEquals(alertsAfterSpin, engine.firedAlerts().size(),
                "round " + round + ": deleted rule must produce no alerts after commit");

        assertEquals(FILLERS + 1, engine.ruleRuntimeCount(),
                "round " + round + ": deleted rule runtime released, not resurrected");
        assertTrue(engine.ruleStatuses().stream().noneMatch(s -> s.ruleName().equals(RULE)),
                "round " + round + ": deleted rule must vanish from the status view");
        log.info("round={} scenario=DELETE input={{threshold=3,events=3,removed while {} evaluators "
                        + "spin}} decision basis: postDeleteRecords={} runtimes={} statuses={}",
                round, EVALUATORS, postDeleteRecords.stream().map(EvaluationRecord::ruleName).toList(),
                engine.ruleRuntimeCount(),
                engine.ruleStatuses().stream().map(RuleStatus::ruleName).toList());
        spin.assertConsistent(round);

        // Recreate the same name: clean slate, fires immediately even though
        // the old incarnation would still be cooling down.
        engine.updateRules(baseRules(true));
        List<AlertEvent> recreated = engine.evaluate().stream()
                .filter(a -> a.ruleName().equals(RULE)).toList();
        assertEquals(1, recreated.size(),
                "round " + round + ": recreated same-name rule starts from a clean state");

        // Repeated add/delete while evaluations keep running never grows.
        for (int i = 0; i < 25; i++) {
            SpinResult again = runSpin(engine, manager, () -> {
                engine.updateRules(baseRules(false));
                engine.updateRules(baseRules(true));
            });
            again.assertConsistent(round);
            assertEquals(FILLERS + 2, engine.ruleRuntimeCount(),
                    "round " + round + " add/delete iteration " + i + ": no state accumulation");
        }
    }

    private static final class SpinResult {
        private final List<String> mismatches;

        SpinResult(List<String> mismatches) {
            this.mismatches = mismatches;
        }

        void assertConsistent(int round) {
            assertTrue(mismatches.isEmpty(),
                    "round " + round + ": concurrent status view / runtime inconsistencies: "
                            + mismatches);
        }
    }

    /**
     * Starts evaluators that keep evaluating at the current instant. The
     * mutator action (plus a stream of rejected submissions) runs while they
     * are in flight; a sampler continuously reads the status view.
     */
    private SpinResult runSpin(AlertEngine engine, AlertRuleManager manager, Runnable mutator)
            throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(EVALUATORS);
        List<String> mismatches = new CopyOnWriteArrayList<>();

        List<Future<?>> evaluators = new ArrayList<>();
        for (int i = 0; i < EVALUATORS; i++) {
            evaluators.add(pool.submit(() -> {
                try {
                    started.countDown();
                    started.await();
                    while (running.get()) {
                        engine.evaluate();
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        started.await(5, TimeUnit.SECONDS);

        Future<?> sampler = pool.submit(() -> {
            while (running.get()) {
                // Single locked consistency read: no orphan (resurrected)
                // runtime can ever be observed under any linearization.
                if (!engine.runtimeMatchesRules()) {
                    mismatches.add("runtime entries do not match committed rules: runtimes="
                            + engine.ruleRuntimeCount()
                            + " rules=" + engine.currentRules().stream().map(AlertRule::name).toList());
                }
                // New definition showing cooldown/firing memory before the
                // new definition has ever fired == half-old/half-new state.
                for (RuleStatus s : engine.ruleStatuses()) {
                    if (s.ruleName().equals(RULE) && s.threshold() == 2
                            && (s.inCooldown() || s.lastFiredAt() != null)) {
                        boolean newDefFired = engine.evaluationLog().stream()
                                .anyMatch(r -> r.ruleName().equals(RULE)
                                        && r.threshold() == 2
                                        && r.decision() == EvaluationRecord.Decision.FIRED);
                        if (!newDefFired) {
                            mismatches.add("new definition paired with old cooldown memory: " + s);
                        }
                    }
                }
            }
        });

        Future<?> invalidSubmitter = pool.submit(() -> {
            while (running.get()) {
                expectRejected(() -> manager.add(new RuleDefinition(null, "GET /x", "5xx",
                        1, WINDOW, COOLDOWN)));
                expectRejected(() -> manager.add(definitionDto(0)));
                expectRejected(() -> manager.update("ghost-rule", definitionDto(1)));
                expectRejected(() -> manager.delete("ghost-rule"));
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        Thread.sleep(30); // evaluations are already in flight
        mutator.run();
        Thread.sleep(60); // let in-flight evaluations cross the commit point
        running.set(false);
        invalidSubmitter.get(5, TimeUnit.SECONDS);
        sampler.get(5, TimeUnit.SECONDS);
        for (Future<?> f : evaluators) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        return new SpinResult(mismatches);
    }

    /**
     * Replays the redefined rule's event stream into an engine that starts
     * directly with the new definition, then drives identical deterministic
     * ticks on both engines and compares every subsequent decision and
     * firing. Cooldown/recovery transitions are exercised on purpose: the
     * window empties (recovery), new events arrive, and the rule fires again.
     */
    private void assertDecisionsMatchFreshEngine(int round, MutableClock clock, AlertEngine engine,
                                                 List<Instant> events) {
        // Same clock instance: identical time progression for both engines.
        AlertEngine fresh = new AlertEngine(clock, IDLE_TTL);
        fresh.updateRules(List.of(definition(2)));
        for (Instant at : events) {
            ingest(fresh, at, 1);
        }
        fresh.evaluate();
        EvaluationRecord freshFirst = lastRecord(fresh);
        EvaluationRecord liveFirst = recordsForRule(engine).stream()
                .filter(r -> r.threshold() == 2)
                .findFirst().orElseThrow();
        assertEquals(EvaluationRecord.Decision.FIRED, freshFirst.decision(),
                "round " + round + ": a fresh engine with the new definition fires immediately");
        assertEquals(liveFirst.decision(), freshFirst.decision(),
                "round " + round + ": first new-definition decision matches a fresh engine");
        assertEquals(liveFirst.windowCount(), freshFirst.windowCount(),
                "round " + round + ": first new-definition window count matches a fresh engine");

        List<Integer> extraPerTick = List.of(0, 0, 0, 1, 3, 2, 0, 0, 0, 3, 3, 0, 0, 0, 0, 3);
        int freshAlerts = 0;
        int liveAlerts = 0;
        for (int tick = 0; tick < extraPerTick.size(); tick++) {
            clock.advance(Duration.ofSeconds(30));
            Instant now = clock.instant();
            int extra = extraPerTick.get(tick);
            ingest(engine, now, extra);
            ingest(fresh, now, extra);
            List<AlertEvent> liveFired = engine.evaluate().stream()
                    .filter(a -> a.ruleName().equals(RULE)).toList();
            List<AlertEvent> freshFired = fresh.evaluate().stream()
                    .filter(a -> a.ruleName().equals(RULE)).toList();
            EvaluationRecord liveLast = lastRecord(engine);
            EvaluationRecord freshLast = lastRecord(fresh);
            log.info("round={} tick={} input={{eventsAdded={}, now={}}} decision basis: "
                            + "live={{count={},decision={},fired={}}} fresh={{count={},decision={},fired={}}}",
                    round, tick, extra, now,
                    liveLast.windowCount(), liveLast.decision(), liveFired.size(),
                    freshLast.windowCount(), freshLast.decision(), freshFired.size());
            assertEquals(freshLast.decision(), liveLast.decision(),
                    "round " + round + " tick " + tick
                            + ": redefined rule must decide exactly like a fresh rule");
            assertEquals(freshLast.windowCount(), liveLast.windowCount(),
                    "round " + round + " tick " + tick + ": window counts must match");
            assertEquals(freshFired.size(), liveFired.size(),
                    "round " + round + " tick " + tick + ": firing outcomes must match");
            liveAlerts += liveFired.size();
            freshAlerts += freshFired.size();
        }
        assertEquals(freshAlerts, liveAlerts,
                "round " + round + ": cumulative firings after redefinition must match a rule "
                        + "that started with the new definition");
    }

    private static EvaluationRecord lastRecord(AlertEngine engine) {
        List<EvaluationRecord> records = recordsForRule(engine);
        return records.get(records.size() - 1);
    }

}
