package com.github.lechandonga.springobservabilityalertingjava.alerting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * 阈值告警评估引擎。
 *
 * 语义保证：
 * <ul>
 *   <li>滑动窗口按秒分桶，{@link #record} 与 {@link #evaluate} 均按 tick 推进，
 *       结果只依赖 (tick, 样本序列)，纯函数式可复现。</li>
 *   <li>触发后进入冷却：冷却期内即使持续越限也只产生
 *       {@link AlertDecision#SUPPRESSED_IN_COOLDOWN}，绝不重复触发；
 *       冷却结束后还必须观测到一次回落（RECOVERED/BELOW_THRESHOLD）才重新武装。</li>
 *   <li>规则集以不可变快照整体替换，评估线程拿到的要么是旧全集要么是新全集，原子生效。</li>
 *   <li>长期无数据的序列在 {@link #evictIdle(long, int)} 中回收（窗口+容忍期），
 *       规则被删除时其状态同步清理。</li>
 * </ul>
 */
public final class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private final LongSupplier clockSeconds;
    private final int maxEventHistory;

    private volatile Map<String, AlertRule> rules = Map.of();
    private final ConcurrentHashMap<RuleSeriesKey, SlidingWindowCounter> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RuleSeriesKey, RuleState> states = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AlertEvent> events = new CopyOnWriteArrayList<>();

    public AlertEngine(LongSupplier clockSeconds) {
        this(clockSeconds, 10_000);
    }

    AlertEngine(LongSupplier clockSeconds, int maxEventHistory) {
        this.clockSeconds = clockSeconds;
        this.maxEventHistory = maxEventHistory;
    }

    /**
     * 原子替换整套规则：评估线程永远看到一致的规则快照。
     * 不再存在的规则，其窗口与状态立即回收。
     */
    public void replaceRules(List<AlertRule> newRules) {
        Map<String, AlertRule> snapshot = new HashMap<>();
        for (AlertRule rule : newRules) {
            if (snapshot.put(rule.id(), rule) != null) {
                throw new IllegalArgumentException("duplicated rule id: " + rule.id());
            }
        }
        Map<String, AlertRule> previous = this.rules;
        this.rules = Map.copyOf(snapshot);

        // 规则被删除、或定义发生任何变化（窗口/阈值/冷却/匹配维度），
        // 其旧窗口与状态都必须随原子快照一并作废，避免用旧结论解释新规则。
        windows.keySet().removeIf(key -> !this.rules.getOrDefault(key.ruleId(), null)
                .equals(previous.get(key.ruleId())));
        states.keySet().removeIf(key -> !this.rules.getOrDefault(key.ruleId(), null)
                .equals(previous.get(key.ruleId())));
        log.info("alert rules replaced atomically, ruleCount={}", this.rules.size());
    }

    /**
     * 写入一次观测。同一条样本会计入所有匹配的规则（status 支持 "*"）。
     */
    public void record(String endpoint, String status, long calls, long errors, long tickSeconds) {
        for (AlertRule rule : rules.values()) {
            if (!rule.matches(endpoint, status)) {
                continue;
            }
            long amount = rule.metric() == AlertMetric.ERRORS ? errors : calls;
            if (amount == 0) {
                continue;
            }
            RuleSeriesKey key = new RuleSeriesKey(rule.id(), endpoint, status);
            SlidingWindowCounter window = windows.computeIfAbsent(key,
                    ignored -> new SlidingWindowCounter(rule.windowSeconds(), tickSeconds));
            window.add(amount, tickSeconds);
        }
    }

    /** 使用注入时钟的便捷入口。 */
    public List<AlertEvent> evaluate() {
        return evaluate(clockSeconds.getAsLong());
    }

    /**
     * 在给定 tick 评估全部规则 × 序列，返回本 tick 产生的事件（FIRED 等）。
     * 同一规则同一 tick 的结论唯一；冷却期内绝无第二个 FIRED。
     */
    public List<AlertEvent> evaluate(long tickSeconds) {
        List<AlertEvent> produced = new ArrayList<>();
        for (RuleSeriesKey key : List.copyOf(windows.keySet())) {
            AlertRule rule = rules.get(key.ruleId());
            SlidingWindowCounter window = windows.get(key);
            if (rule == null || window == null) {
                continue;
            }
            long value = window.windowSum(tickSeconds);
            RuleState state = states.computeIfAbsent(key, ignored -> new RuleState());

            AlertDecision decision;
            if (value >= rule.threshold()) {
                boolean cooling = state.lastFireTick() != Long.MIN_VALUE
                        && tickSeconds - state.lastFireTick() < rule.cooldownSeconds();
                if (cooling) {
                    decision = AlertDecision.SUPPRESSED_IN_COOLDOWN;
                } else if (!state.armed()) {
                    decision = AlertDecision.NOT_REARMED;
                } else {
                    decision = AlertDecision.FIRED;
                    state.markFired(tickSeconds);
                }
            } else {
                decision = state.armed() ? AlertDecision.BELOW_THRESHOLD : AlertDecision.RECOVERED;
                state.rearm();
            }

            AlertEvent event = new AlertEvent(rule.id(), key.endpoint(), key.status(),
                    tickSeconds, value, rule.threshold(), decision);
            if (decision == AlertDecision.FIRED) {
                log.warn("ALERT FIRED ruleId={} endpoint={} status={} windowValue={} threshold={} tick={}",
                        rule.id(), key.endpoint(), key.status(), value, rule.threshold(), tickSeconds);
            } else {
                log.info("alert evaluated ruleId={} endpoint={} status={} windowValue={} threshold={} decision={} tick={}",
                        rule.id(), key.endpoint(), key.status(), value, rule.threshold(), decision, tickSeconds);
            }
            appendEvent(event);
            produced.add(event);
        }
        return produced;
    }

    /**
     * 回收长期无数据序列：最后一次数据早于 {@code tick - windowSeconds - graceSeconds}
     * 的窗口与状态被删除，避免维度/序列空间随时间无限膨胀。
     *
     * @return 实际回收的序列数量
     */
    public int evictIdle(long tickSeconds, int graceSeconds) {
        int[] removed = {0};
        windows.entrySet().removeIf(entry -> {
            AlertRule rule = rules.get(entry.getKey().ruleId());
            if (rule == null) {
                return true;
            }
            long lastData = entry.getValue().lastDataTick();
            boolean idle = lastData == Long.MIN_VALUE
                    || lastData < tickSeconds - rule.windowSeconds() - graceSeconds;
            if (idle) {
                states.remove(entry.getKey());
                removed[0]++;
            }
            return idle;
        });
        if (removed[0] > 0) {
            log.info("evicted idle alert series, count={}, tick={}", removed[0], tickSeconds);
        }
        return removed[0];
    }

    private void appendEvent(AlertEvent event) {
        events.add(event);
        int overflow = events.size() - maxEventHistory;
        for (int i = 0; i < overflow; i++) {
            events.remove(0);
        }
    }

    /** 评估流水（有界副本），用于排查与“历史结论可复现”的断言。 */
    public List<AlertEvent> eventHistory() {
        return List.copyOf(events);
    }

    public List<AlertRule> currentRules() {
        return List.copyOf(rules.values());
    }

    public int activeSeriesCount() {
        return windows.size();
    }

    private record RuleSeriesKey(String ruleId, String endpoint, String status) {
    }
}
