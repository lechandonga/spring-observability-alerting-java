package com.github.lechandonga.springobservabilityalertingjava.alerting;

/**
 * 规则在单个匹配序列上的状态机（可变，但仅在引擎锁内变更）。
 * armed=true 表示允许下一次越限触发；触发后置 false，
 * 直到观测到指标回落到阈值以下才重新置 true。
 */
final class RuleState {

    private boolean armed = true;
    private long lastFireTick = Long.MIN_VALUE;

    long lastFireTick() {
        return lastFireTick;
    }

    boolean armed() {
        return armed;
    }
    void markFired(long tick) {
        this.armed = false;
        this.lastFireTick = tick;
    }

    void rearm() {
        this.armed = true;
    }
}
