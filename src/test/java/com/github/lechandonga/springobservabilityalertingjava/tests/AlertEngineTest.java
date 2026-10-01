package com.github.lechandonga.springobservabilityalertingjava.tests;

import com.github.lechandonga.springobservabilityalertingjava.alerting.AlertDecision;
import com.github.lechandonga.springobservabilityalertingjava.alerting.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.alerting.AlertEvent;
import com.github.lechandonga.springobservabilityalertingjava.alerting.AlertMetric;
import com.github.lechandonga.springobservabilityalertingjava.alerting.AlertRule;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警冷却/重武装/原子更新/空闲回收场景。
 * 评估使用手动 tick，结论只取决于 (规则, tick, 样本)，历史可精确复现。
 */
class AlertEngineTest {

    private static final Logger log = LoggerFactory.getLogger(AlertEngineTest.class);

    private AlertRule rule(int windowSeconds, long threshold, int cooldownSeconds) {
        return new AlertRule("r1", "/api", "500", AlertMetric.CALLS,
                windowSeconds, threshold, cooldownSeconds);
    }

    private void feed(AlertEngine engine, long tick, long amount) {
        engine.record("/api", "500", amount, 0, tick);
    }

    private AlertDecision lastDecision(AlertEngine engine) {
        List<AlertEvent> history = engine.eventHistory();
        return history.get(history.size() - 1).decision();
    }

    @Test
    void firesOnceDuringCooldownThenRearmsAfterRecovery() {
        AlertEngine engine = new AlertEngine(() -> 0L);
        engine.replaceRules(List.of(rule(10, 3, 5)));

        // tick 1: 窗口内 3 >= 阈值 => FIRED
        feed(engine, 1, 3);
        AlertDecision at1 = engine.evaluate(1).get(0).decision();
        log.info("tick=1 window=3 threshold=3 => {} (expected FIRED)", at1);
        assertEquals(AlertDecision.FIRED, at1);

        // tick 2/3: 仍越限，但冷却期内 => SUPPRESSED_IN_COOLDOWN，绝不重复触发
        feed(engine, 2, 1);
        AlertDecision at2 = engine.evaluate(2).get(0).decision();
        feed(engine, 3, 1);
        AlertDecision at3 = engine.evaluate(3).get(0).decision();
        log.info("tick=2 window=4 => {}; tick=3 window=5 => {} (expected suppressed)", at2, at3);
        assertEquals(AlertDecision.SUPPRESSED_IN_COOLDOWN, at2);
        assertEquals(AlertDecision.SUPPRESSED_IN_COOLDOWN, at3);

        // tick 6: 冷却已过，但窗口仍 >= 阈值且从未回落 => NOT_REARMED
        AlertDecision at6 = engine.evaluate(6).get(0).decision();
        log.info("tick=6 cooldown elapsed but window={} still >= threshold => {} (expected NOT_REARMED)",
                engine.eventHistory().get(engine.eventHistory().size() - 1).windowValue(), at6);
        assertEquals(AlertDecision.NOT_REARMED, at6);

        // tick 13: 窗口滑过所有样本 => 回落，重新武装
        AlertDecision at13 = engine.evaluate(13).get(0).decision();
        log.info("tick=13 window=0 => {} (expected RECOVERED)", at13);
        assertEquals(AlertDecision.RECOVERED, at13);

        // tick 14: 再次越限 => 允许再次 FIRED
        feed(engine, 14, 3);
        AlertDecision at14 = engine.evaluate(14).get(0).decision();
        log.info("tick=14 window=3 after recovery => {} (expected FIRED again)", at14);
        assertEquals(AlertDecision.FIRED, at14);

        long firedCount = engine.eventHistory().stream()
                .filter(event -> event.decision() == AlertDecision.FIRED).count();
        assertEquals(2L, firedCount, "exactly two firings across the reproduced history");
    }

    @Test
    void ruleReplacementIsAtomicAndReclaimsOldSeries() {
        AlertEngine engine = new AlertEngine(() -> 0L);
        engine.replaceRules(List.of(rule(10, 1, 5)));
        feed(engine, 1, 1);
        engine.evaluate(1);
        assertEquals(1, engine.activeSeriesCount());

        AlertRule changed = new AlertRule("r1", "/api", "500", AlertMetric.CALLS, 10, 100, 5);
        long start = System.nanoTime();
        engine.replaceRules(List.of(changed));
        log.info("rule replaced atomically in {}ns; old series must be reclaimed",
                System.nanoTime() - start);

        assertEquals(0, engine.activeSeriesCount(), "series of removed/replaced rule must be reclaimed");
        feed(engine, 2, 50);
        assertEquals(AlertDecision.BELOW_THRESHOLD, engine.evaluate(2).get(0).decision());
    }

    @Test
    void idleSeriesAreEvictedAfterWindowPlusGrace() {
        AlertEngine engine = new AlertEngine(() -> 0L);
        engine.replaceRules(List.of(rule(5, 100, 0)));
        feed(engine, 10, 1);
        engine.evaluate(10);
        assertEquals(1, engine.activeSeriesCount());

        int evictedAtWindow = engine.evictIdle(15, 5);
        log.info("eviction tick=15 (windowEnd=15 graceEnd=20) removed={} (expected 0)", evictedAtWindow);
        assertEquals(0, evictedAtWindow);

        int evictedAfterGrace = engine.evictIdle(21, 5);
        log.info("eviction tick=21 (graceEnd=20) removed={} (expected 1)", evictedAfterGrace);
        assertEquals(1, evictedAfterGrace);
        assertEquals(0, engine.activeSeriesCount());
    }

    @Test
    void historyIsReproducibleFromSameInputs() {
        AlertEngine first = new AlertEngine(() -> 0L);
        AlertEngine second = new AlertEngine(() -> 0L);
        List<AlertRule> rules = List.of(rule(4, 2, 3));
        first.replaceRules(rules);
        second.replaceRules(rules);

        for (long tick = 1; tick <= 8; tick++) {
            long amount = (tick == 1 || tick == 5) ? 2 : 0;
            if (amount > 0) {
                feed(first, tick, amount);
                feed(second, tick, amount);
            }
            first.evaluate(tick);
            second.evaluate(tick);
        }
        List<AlertEvent> a = first.eventHistory();
        List<AlertEvent> b = second.eventHistory();
        log.info("replayed history size={} decisionsA={}", a.size(),
                a.stream().map(AlertEvent::decision).toList());
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).decision(), b.get(i).decision());
            assertEquals(a.get(i).windowValue(), b.get(i).windowValue());
            assertEquals(a.get(i).tickSeconds(), b.get(i).tickSeconds());
        }
        assertFalse(a.isEmpty());
        assertTrue(a.stream().anyMatch(event -> event.decision() == AlertDecision.FIRED));
    }
}
