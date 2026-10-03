package com.github.lechandonga.springobservabilityalertingjava.tests.alert;

import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRule;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRuleManager;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleDefinition;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertRuleManagerTest {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleManagerTest.class);

    private MutableClock clock;
    private AlertEngine engine;
    private AlertRuleManager manager;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-02T00:00:00Z"));
        engine = new AlertEngine(clock, Duration.ofMinutes(10));
        manager = new AlertRuleManager(engine, 3);
    }

    private static RuleDefinition def(String name, int threshold) {
        return new RuleDefinition(name, "GET /api/fail", "5xx", threshold,
                Duration.ofMinutes(1), Duration.ofMinutes(2));
    }

    private static RuleValidationException.Reason reasonOf(Runnable op) {
        return assertThrows(RuleValidationException.class, op::run).reason();
    }

    @Test
    void validationRejectsDistinctReasons() {
        manager.add(def("a", 3));
        log.info("input: missing/invalid/duplicate/unknown/over-limit changes");
        assertEquals(RuleValidationException.Reason.MISSING_FIELD,
                reasonOf(() -> manager.add(new RuleDefinition(null, "GET /x", "5xx", 1,
                        Duration.ofMinutes(1), Duration.ZERO))));
        assertEquals(RuleValidationException.Reason.MISSING_FIELD,
                reasonOf(() -> manager.add(new RuleDefinition("b", "GET /x", "5xx", null,
                        Duration.ofMinutes(1), Duration.ZERO))));
        assertEquals(RuleValidationException.Reason.INVALID_VALUE,
                reasonOf(() -> manager.add(def("b", 0))));
        assertEquals(RuleValidationException.Reason.INVALID_VALUE,
                reasonOf(() -> manager.add(new RuleDefinition("b", "GET /x", "5xx", 1,
                        Duration.ofMinutes(-1), Duration.ZERO))));
        assertEquals(RuleValidationException.Reason.DUPLICATE_NAME,
                reasonOf(() -> manager.add(def("a", 5))));
        assertEquals(RuleValidationException.Reason.RULE_NOT_FOUND,
                reasonOf(() -> manager.update("ghost", def("ghost", 1))));
        assertEquals(RuleValidationException.Reason.RULE_NOT_FOUND,
                reasonOf(() -> manager.delete("ghost")));
        manager.add(def("b", 1));
        manager.add(def("c", 1));
        assertEquals(RuleValidationException.Reason.LIMIT_EXCEEDED,
                reasonOf(() -> manager.add(def("d", 1))));
        log.info("decision basis: rules after rejections={}", manager.list());
        assertEquals(3, manager.list().size(), "rejected changes must not alter the rule set");
    }

    @Test
    void rejectedChangeLeavesRulesAndAlertStateUntouched() {
        AlertRuleManager tight = new AlertRuleManager(engine, 1);
        tight.add(def("hot", 3));
        for (int i = 0; i < 3; i++) {
            engine.recordEvent("GET /api/fail", "5xx", clock.instant());
        }
        engine.evaluate();
        List<AlertRule> rulesBefore = engine.currentRules();
        var firedBefore = engine.firedAlerts();
        var statusBefore = engine.ruleStatuses();
        int runtimesBefore = engine.ruleRuntimeCount();

        assertThrows(RuleValidationException.class, () -> tight.add(def("hot", 9)));
        assertThrows(RuleValidationException.class, () -> tight.add(def("extra1", 1)));
        assertThrows(RuleValidationException.class, () -> tight.add(def("extra2", 1)));
        assertThrows(RuleValidationException.class, () -> tight.update("hot", def("hot", 0)));
        assertThrows(RuleValidationException.class, () -> tight.delete("nope"));

        log.info("decision basis: rules={} fired={} status={}",
                engine.currentRules(), engine.firedAlerts(), engine.ruleStatuses());
        assertEquals(rulesBefore, engine.currentRules(), "rules must be untouched");
        assertEquals(firedBefore, engine.firedAlerts(), "fired alerts must be untouched");
        assertEquals(statusBefore, engine.ruleStatuses(), "rule status must be untouched");
        assertEquals(runtimesBefore, engine.ruleRuntimeCount(), "runtime state must be untouched");
    }

    @Test
    void limitIsHardUnderConcurrency() throws InterruptedException {
        AlertRuleManager tight = new AlertRuleManager(engine, 50);
        int threads = 8;
        int attemptsPerThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger added = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            int base = t * attemptsPerThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < attemptsPerThread; i++) {
                        try {
                            tight.add(def("r" + (base + i), 1));
                            added.incrementAndGet();
                        } catch (RuleValidationException e) {
                            assertEquals(RuleValidationException.Reason.LIMIT_EXCEEDED, e.reason());
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("decision basis: added={} rejected={} finalSize={} limit={}",
                added.get(), rejected.get(), tight.list().size(), tight.maxRules());
        assertEquals(50, added.get(), "exactly the limit may be added");
        assertEquals(50, tight.list().size(), "limit must never be exceeded");
        assertEquals(threads * attemptsPerThread - 50, rejected.get());
    }

    @Test
    void concurrentUpdatesAndEvaluationsStayConsistent() throws InterruptedException {
        manager.add(def("hot", 3));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger evaluations = new AtomicInteger();
        pool.submit(() -> {
            for (int i = 0; i < 200; i++) {
                engine.recordEvent("GET /api/fail", "5xx", clock.instant());
                engine.evaluate();
                evaluations.incrementAndGet();
            }
        });
        pool.submit(() -> {
            for (int i = 1; i <= 200; i++) {
                manager.update("hot", def("hot", 1 + (i % 5)));
            }
        });
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("decision basis: evaluations={} finalRules={} logSize={}",
                evaluations.get(), manager.list(), engine.evaluationLog().size());
        assertEquals(1, manager.list().size(), "rule set must stay a single whole rule");
        assertEquals(200, engine.evaluationLog().size(),
                "every evaluation must record exactly one whole-rule decision");
        assertTrue(engine.evaluationLog().stream().allMatch(r -> r.ruleName().equals("hot")));
    }
}
