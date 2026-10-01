package com.github.lechandonga.springobservabilityalertingjava.alerting;

/**
 * 评估流水事件（不可变）。同一份 (规则, 逐tick输入) 重放必然产生相同事件序列，
 * 因此历史结论可复现、可审计。
 */
public record AlertEvent(
        String ruleId,
        String endpoint,
        String status,
        long tickSeconds,
        long windowValue,
        long threshold,
        AlertDecision decision) {
}
