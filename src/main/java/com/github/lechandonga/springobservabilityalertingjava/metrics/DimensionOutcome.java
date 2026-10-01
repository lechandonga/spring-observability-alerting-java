package com.github.lechandonga.springobservabilityalertingjava.metrics;

/**
 * 维度值经过基数（cardinality）策略处理后的判定结果。
 * <ul>
 *   <li>{@link #ACCEPTED}：维度合法且未超过序列上限，按原值计入独立序列。</li>
 *   <li>{@link #INVALID}：维度缺失或不符合字符/长度规范，归并到常量桶 {@code __invalid__}。</li>
 *   <li>{@link #CAPPED}：维度合法但活跃序列数已达上限，归并到常量桶 {@code __other__}。</li>
 * </ul>
 * 拒绝与归并都会被独立计数（{@code invalid_dimension_total} /
 * {@code capped_dimension_total}），既不会静默丢弃，也不会让维度空间无限增长。
 */
public enum DimensionOutcome {
    ACCEPTED,
    INVALID,
    CAPPED
}
