package com.github.lechandonga.springobservabilityalertingjava.metrics;

import java.util.List;

/**
 * 整个指标注册表的一致性快照。{@code series} 中每个序列的调用量与时延分布、
 * 以及全局的无效/归并计数，都在同一次读锁内采集，可直接用于断言或输出 JSON。
 */
public record MetricsSnapshot(
        long invalidDimensionRejections,
        long cappedDimensionMerges,
        int maxSeries,
        List<SeriesSnapshot> series) {

    public record SeriesSnapshot(
            String endpoint,
            String status,
            boolean endpointCapped,
            boolean statusCapped,
            long calls,
            long errorCalls,
            HistogramSnapshot latency) {
    }
}
