package com.github.lechandonga.springobservabilityalertingjava.alerting;

/**
 * 一条不可变的告警规则。
 *
 * @param id             规则唯一标识（非空）
 * @param endpoint       匹配的接口维度，常量桶 {@code __other__}/{@code __invalid__} 也可匹配
 * @param status         匹配的结果状态维度，{@code "*"} 表示通配该接口下所有状态序列
 * @param metric         统计口径
 * @param windowSeconds  滑动窗口长度（秒，正整数）
 * @param threshold      触发阈值：窗口内累计量达到该值即满足触发条件
 * @param cooldownSeconds 冷却时长（秒）；触发后在冷却期内绝不重复触发
 */
public record AlertRule(
        String id,
        String endpoint,
        String status,
        AlertMetric metric,
        int windowSeconds,
        long threshold,
        int cooldownSeconds) {

    public AlertRule {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("rule id must not be blank");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("rule endpoint must not be blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("rule status must not be blank");
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive");
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        if (cooldownSeconds < 0) {
            throw new IllegalArgumentException("cooldownSeconds must not be negative");
        }
    }

    public boolean matches(String candidateEndpoint, String candidateStatus) {
        return endpoint.equals(candidateEndpoint)
                && ("*".equals(status) || status.equals(candidateStatus));
    }
}
