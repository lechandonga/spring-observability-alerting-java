package com.github.lechandonga.springobservabilityalertingjava.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sliding-window threshold alerting with cooldown.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Rule updates are atomic for evaluators: the whole rule list lives in
 *       one {@link AtomicReference}, so an evaluation always sees a consistent
 *       snapshot, never a half-applied update.</li>
 *   <li>A rule fires at most once per cooldown period, and only re-arms after
 *       the windowed count dropped below the threshold (recovery).</li>
 *   <li>Series with no events for {@code idleTtl} are evicted, so long-dead
 *       series do not leak memory.</li>
 *   <li>Every evaluation is appended to an audit log; given the same events
 *       and rules the decisions are reproducible.</li>
 * </ul>
 *
 * <p>Event ingestion is thread-safe; evaluation is expected to be driven by a
 * single scheduler thread (see {@code AlertScheduler}), which keeps the state
 * machine simple and the audit log ordered.
 */
public final class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private final Clock clock;
    private final Duration idleTtl;
    private final AtomicReference<List<AlertRule>> rules = new AtomicReference<>(List.of());
    private final ConcurrentHashMap<String, SeriesState> states = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AlertEvent> firedAlerts = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<EvaluationRecord> evaluationLog = new CopyOnWriteArrayList<>();

    public AlertEngine(Clock clock, Duration idleTtl) {
        this.clock = clock;
        this.idleTtl = idleTtl;
    }

    /** Ingests one request event. Safe to call from many request threads. */
    public void recordEvent(String endpoint, String statusClass, Instant at) {
        String series = endpoint + "|" + statusClass;
        SeriesState state = states.computeIfAbsent(series, k -> new SeriesState());
        synchronized (state) {
            state.events.addLast(at);
            state.lastEventAt = at;
        }
    }

    /** Atomically replaces the rule set; evaluators see old or new, never a mix. */
    public void updateRules(List<AlertRule> newRules) {
        rules.set(List.copyOf(newRules));
        log.info("alert rules updated atomically, count={}", newRules.size());
    }

    public List<AlertRule> currentRules() {
        return rules.get();
    }

    /**
     * Evaluates all rules once. Returns the alerts fired in this pass.
     * Must be called from a single thread (the scheduler).
     */
    public List<AlertEvent> evaluate() {
        Instant now = clock.instant();
        evictIdleSeries(now);
        List<AlertEvent> fired = new ArrayList<>();
        for (AlertRule rule : rules.get()) {
            String series = rule.endpoint() + "|" + rule.statusClass();
            SeriesState state = states.get(series);
            long windowCount = countInWindow(state, rule.name(), rule.window(), now);
            EvaluationRecord.Decision decision = decide(rule, state, windowCount, now);
            evaluationLog.add(new EvaluationRecord(rule.name(), series, now, windowCount, rule.threshold(), decision));
            log.debug("evaluate rule={} series={} windowCount={} threshold={} decision={}",
                    rule.name(), series, windowCount, rule.threshold(), decision);
            if (decision == EvaluationRecord.Decision.FIRED) {
                AlertEvent event = new AlertEvent(rule.name(), series, now, windowCount, rule.threshold());
                firedAlerts.add(event);
                fired.add(event);
                log.warn("ALERT fired rule={} series={} windowCount={} threshold={}",
                        rule.name(), series, windowCount, rule.threshold());
            }
        }
        return fired;
    }

    private EvaluationRecord.Decision decide(AlertRule rule, SeriesState state, long windowCount, Instant now) {
        RuleRuntime rt = ruleRuntimes.computeIfAbsent(rule.name(), k -> new RuleRuntime());
        if (windowCount < rule.threshold()) {
            rt.recovered = true;
            return EvaluationRecord.Decision.OK;
        }
        if (rt.lastFiredAt != null
                && Duration.between(rt.lastFiredAt, now).compareTo(rule.cooldown()) < 0) {
            return EvaluationRecord.Decision.SUPPRESSED_BY_COOLDOWN;
        }
        if (!rt.recovered) {
            return EvaluationRecord.Decision.SUPPRESSED_NOT_RECOVERED;
        }
        rt.lastFiredAt = now;
        rt.recovered = false;
        return EvaluationRecord.Decision.FIRED;
    }

    private long countInWindow(SeriesState state, String ruleName, Duration window, Instant now) {
        if (state == null) {
            return 0;
        }
        Instant cutoff = now.minus(window);
        synchronized (state) {
            while (!state.events.isEmpty() && state.events.peekFirst().isBefore(cutoff)) {
                state.events.pollFirst();
            }
            return state.events.size();
        }
    }

    private void evictIdleSeries(Instant now) {
        states.entrySet().removeIf(e -> {
            SeriesState s = e.getValue();
            synchronized (s) {
                boolean idle = s.lastEventAt != null
                        && Duration.between(s.lastEventAt, now).compareTo(idleTtl) > 0;
                if (idle) {
                    log.info("evicting idle series={} lastEventAt={}", e.getKey(), s.lastEventAt);
                }
                return idle;
            }
        });
    }

    public List<AlertEvent> firedAlerts() {
        return List.copyOf(firedAlerts);
    }

    public List<EvaluationRecord> evaluationLog() {
        return List.copyOf(evaluationLog);
    }

    public int trackedSeriesCount() {
        return states.size();
    }

    private final Map<String, RuleRuntime> ruleRuntimes = new ConcurrentHashMap<>();

    private static final class SeriesState {
        final Deque<Instant> events = new ArrayDeque<>();
        Instant lastEventAt;
    }

    private static final class RuleRuntime {
        Instant lastFiredAt;
        boolean recovered = true;
    }
}
