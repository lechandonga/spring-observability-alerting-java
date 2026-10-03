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
        List<AlertRule> copy = List.copyOf(newRules);
        Map<String, AlertRule> previous = byName(rules.get());
        Map<String, AlertRule> next = byName(copy);
        rules.set(copy);
        // Turn the page on changed or deleted rules: their trigger/cooldown/
        // recovery state is dropped, so a redefined rule starts from a clean
        // slate and a deleted rule releases its state. Windowed event data is
        // kept per series and still counts toward the new definition.
        ruleRuntimes.keySet().removeIf(name ->
                !next.containsKey(name) || !next.get(name).equals(previous.get(name)));
        log.info("alert rules updated atomically, count={}", newRules.size());
    }

    private static Map<String, AlertRule> byName(List<AlertRule> ruleList) {
        Map<String, AlertRule> map = new ConcurrentHashMap<>();
        for (AlertRule rule : ruleList) {
            map.put(rule.name(), rule);
        }
        return map;
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
            EvaluationRecord.Decision decision = decide(rule, windowCount, now);
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

    private EvaluationRecord.Decision decide(AlertRule rule, long windowCount, Instant now) {
        RuleRuntime rt = ruleRuntimes.computeIfAbsent(rule.name(), k -> new RuleRuntime());
        rt.lastEvaluatedAt = now;
        rt.lastWindowCount = windowCount;
        EvaluationRecord.Decision decision;
        if (windowCount < rule.threshold()) {
            rt.recovered = true;
            decision = EvaluationRecord.Decision.OK;
        } else if (rt.lastFiredAt != null
                && Duration.between(rt.lastFiredAt, now).compareTo(rule.cooldown()) < 0) {
            decision = EvaluationRecord.Decision.SUPPRESSED_BY_COOLDOWN;
        } else if (!rt.recovered) {
            decision = EvaluationRecord.Decision.SUPPRESSED_NOT_RECOVERED;
        } else {
            rt.lastFiredAt = now;
            rt.recovered = false;
            decision = EvaluationRecord.Decision.FIRED;
        }
        rt.lastDecision = decision;
        return decision;
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

    /** Number of live per-rule runtime entries; exposed for leak checks. */
    public int ruleRuntimeCount() {
        return ruleRuntimes.size();
    }

    /**
     * Snapshot of each current rule's runtime status, consistent with the
     * evaluation log. Read-only: never mutates evaluator state. Rules that
     * were deleted or redefined since the last evaluation show up with their
     * fresh (empty) runtime, or not at all.
     */
    public List<RuleStatus> ruleStatuses() {
        Instant now = clock.instant();
        List<RuleStatus> statuses = new ArrayList<>();
        for (AlertRule rule : rules.get()) {
            RuleRuntime rt = ruleRuntimes.get(rule.name());
            if (rt == null) {
                statuses.add(new RuleStatus(rule.name(), null, 0, rule.threshold(),
                        null, false, Duration.ZERO, true, null));
                continue;
            }
            boolean inCooldown = false;
            Duration remaining = Duration.ZERO;
            if (rt.lastFiredAt != null) {
                Duration elapsed = Duration.between(rt.lastFiredAt, now);
                if (elapsed.compareTo(rule.cooldown()) < 0) {
                    inCooldown = true;
                    remaining = rule.cooldown().minus(elapsed);
                }
            }
            statuses.add(new RuleStatus(rule.name(), rt.lastEvaluatedAt, rt.lastWindowCount,
                    rule.threshold(), rt.lastDecision, inCooldown, remaining,
                    rt.recovered, rt.lastFiredAt));
        }
        return statuses;
    }

    private final Map<String, RuleRuntime> ruleRuntimes = new ConcurrentHashMap<>();

    private static final class SeriesState {
        final Deque<Instant> events = new ArrayDeque<>();
        Instant lastEventAt;
    }

    private static final class RuleRuntime {
        volatile Instant lastFiredAt;
        volatile boolean recovered = true;
        volatile Instant lastEvaluatedAt;
        volatile long lastWindowCount;
        volatile EvaluationRecord.Decision lastDecision;
    }
}
