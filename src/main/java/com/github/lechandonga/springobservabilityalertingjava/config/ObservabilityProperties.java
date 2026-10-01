package com.github.lechandonga.springobservabilityalertingjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 指标维度与直方图相关配置，前缀 {@code observability.metrics}。
 */
@ConfigurationProperties(prefix = "observability.metrics")
public class ObservabilityProperties {

    /** endpoint 维度允许的不同取值数量上限（不含 __invalid__/__other__ 两个常量桶）。 */
    private int maxEndpointValues = 100;
    /** status 维度允许的不同取值数量上限。 */
    private int maxStatusValues = 16;
    /** 单个 endpoint 值的最大长度，超出按非法维度拒绝。 */
    private int maxEndpointLength = 128;
    /** 时延直方图桶上界（毫秒，升序），最后一个桶之后计入 overflow。 */
    private long[] latencyBucketsMillis = {5, 10, 25, 50, 100, 250, 500, 1000, 2500};

    public int getMaxEndpointValues() {
        return maxEndpointValues;
    }

    public void setMaxEndpointValues(int maxEndpointValues) {
        this.maxEndpointValues = maxEndpointValues;
    }

    public int getMaxStatusValues() {
        return maxStatusValues;
    }

    public void setMaxStatusValues(int maxStatusValues) {
        this.maxStatusValues = maxStatusValues;
    }

    public int getMaxEndpointLength() {
        return maxEndpointLength;
    }

    public void setMaxEndpointLength(int maxEndpointLength) {
        this.maxEndpointLength = maxEndpointLength;
    }

    public long[] getLatencyBucketsMillis() {
        return latencyBucketsMillis;
    }

    public void setLatencyBucketsMillis(long[] latencyBucketsMillis) {
        this.latencyBucketsMillis = latencyBucketsMillis;
    }
}
