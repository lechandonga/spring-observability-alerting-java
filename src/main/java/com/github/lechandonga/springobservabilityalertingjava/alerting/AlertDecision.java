package com.github.lechandonga.springobservabilityalertingjava.alerting;

/**
 * 单次评估结论。四类结论互斥可区分，历史流可据此完整复盘状态机：
 * <ul>
 *   <li>{@link #FIRED}：达到阈值且规则已重新武装（冷却已过 + 期间观测过回落）。</li>
 *   <li>{@link #SUPPRESSED_IN_COOLDOWN}：仍满足阈值，但处于冷却期内，不重复触发。</li>
 *   <li>{@link #NOT_REARMED}：冷却已过，但阈值从未回落过，不重复触发。</li>
 *   <li>{@link #RECOVERED}：指标回落到阈值以下，规则重新武装。</li>
 *   <li>{@link #BELOW_THRESHOLD}：未达阈值（含初始态），不触发。</li>
 * </ul>
 */
public enum AlertDecision {
    FIRED,
    SUPPRESSED_IN_COOLDOWN,
    NOT_REARMED,
    RECOVERED,
    BELOW_THRESHOLD
}
