package com.github.lechandonga.springobservabilityalertingjava.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

/**
 * 请求级指标注册表：按 (endpoint, status) 两个维度累计调用量与时延分布。
 *
 * 维度基数有硬性上限：endpoint/status 各自允许的不同取值数量受配置约束，
 * 超限值归并到 {@link #CAPPED_BUCKET}，非法值归并到 {@link #INVALID_BUCKET}，
 * 两条拒绝路径分别由独立计数器暴露，任何一次记录都不会被静默丢弃。
 * 序列 map 的结构性变更（新建/拒绝判定）在 {@link StampedLock} 写锁内完成，
 * 而计数与直方图更新使用原子量/内部锁，保证高并发下不丢计数、快照自洽。
 */
public final class MetricsRegistry {

    public static final String INVALID_BUCKET = "__invalid__";
    public static final String CAPPED_BUCKET = "__other__";

    private static final Logger log = LoggerFactory.getLogger(MetricsRegistry.class);

    private final int maxEndpointValues;
    private final int maxStatusValues;
    private final int maxEndpointLength;
    private final long[] histogramBounds;

    private final Map<String, Boolean> knownEndpoints = new ConcurrentHashMap<>();
    private final Map<String, Boolean> knownStatuses = new ConcurrentHashMap<>();
    private final Map<SeriesKey, Series> seriesByKey = new ConcurrentHashMap<>();
    private final AtomicLong invalidDimensionRejections = new AtomicLong();
    private final AtomicLong cappedDimensionMerges = new AtomicLong();

    private final StampedLock cardinalityLock = new StampedLock();

    public MetricsRegistry(int maxEndpointValues, int maxStatusValues,
                           int maxEndpointLength, long[] histogramBounds) {
        if (maxEndpointValues < 1 || maxStatusValues < 1) {
            throw new IllegalArgumentException("dimension cardinality limits must be positive");
        }
        this.maxEndpointValues = maxEndpointValues;
        this.maxStatusValues = maxStatusValues;
        this.maxEndpointLength = maxEndpointLength;
        this.histogramBounds = histogramBounds;
    }

    /**
     * 记录一次请求。
     *
     * @param rawEndpoint 接口标识（路径模板），{@code null} 或空白视为非法
     * @param rawStatus   结果状态（HTTP 状态码或 ERROR_*），{@code null} 或空白视为非法
     * @param latencyMillis 时延（毫秒），不允许为负
     * @param error       是否为异常结果
     * @return 两个维度的判定结果，调用方可据此区分“正常/非法/归并”
     */
    public DimensionResult record(String rawEndpoint, String rawStatus,
                                  long latencyMillis, boolean error) {
        DimensionResult dimensions = resolveDimensions(rawEndpoint, rawStatus);
        SeriesKey key = dimensions.key();
        seriesByKey.computeIfAbsent(key, ignored -> new Series(
                key.endpoint(), key.status(),
                dimensions.endpointOutcome() == DimensionOutcome.CAPPED,
                dimensions.statusOutcome() == DimensionOutcome.CAPPED,
                histogramBounds))
                .record(latencyMillis, error);
        return dimensions;
    }

    /**
     * 维度规范化与基数裁决。两个维度的裁决在同一张写锁下串行完成，
     * 因此并发冲击上限时“第 N 个新值被接受、其余被归并”的结论是确定的：
     * 被接受的不同值数量恰好等于上限。
     */
    DimensionResult resolveDimensions(String rawEndpoint, String rawStatus) {
        DimensionOutcome endpointOutcome;
        DimensionOutcome statusOutcome;
        String endpoint;
        String status;

        long stamp = cardinalityLock.writeLock();
        try {
            if (!isValidEndpoint(rawEndpoint)) {
                endpoint = INVALID_BUCKET;
                endpointOutcome = DimensionOutcome.INVALID;
                invalidDimensionRejections.incrementAndGet();
                log.warn("reject invalid endpoint dimension, raw={}, bucket={}", rawEndpoint, INVALID_BUCKET);
            } else {
                String trimmed = rawEndpoint.trim();
                if (knownEndpoints.containsKey(trimmed)) {
                    endpoint = trimmed;
                    endpointOutcome = DimensionOutcome.ACCEPTED;
                } else if (knownEndpoints.size() < maxEndpointValues) {
                    knownEndpoints.put(trimmed, Boolean.TRUE);
                    endpoint = trimmed;
                    endpointOutcome = DimensionOutcome.ACCEPTED;
                } else {
                    endpoint = CAPPED_BUCKET;
                    endpointOutcome = DimensionOutcome.CAPPED;
                    cappedDimensionMerges.incrementAndGet();
                    log.warn("cap endpoint dimension, raw={}, distinctLimit={}, bucket={}",
                            trimmed, maxEndpointValues, CAPPED_BUCKET);
                }
            }

            if (!isValidStatus(rawStatus)) {
                status = INVALID_BUCKET;
                statusOutcome = DimensionOutcome.INVALID;
                invalidDimensionRejections.incrementAndGet();
                log.warn("reject invalid status dimension, raw={}, bucket={}", rawStatus, INVALID_BUCKET);
            } else {
                String trimmed = rawStatus.trim();
                if (knownStatuses.containsKey(trimmed)) {
                    status = trimmed;
                    statusOutcome = DimensionOutcome.ACCEPTED;
                } else if (knownStatuses.size() < maxStatusValues) {
                    knownStatuses.put(trimmed, Boolean.TRUE);
                    status = trimmed;
                    statusOutcome = DimensionOutcome.ACCEPTED;
                } else {
                    status = CAPPED_BUCKET;
                    statusOutcome = DimensionOutcome.CAPPED;
                    cappedDimensionMerges.incrementAndGet();
                    log.warn("cap status dimension, raw={}, distinctLimit={}, bucket={}",
                            trimmed, maxStatusValues, CAPPED_BUCKET);
                }
            }
        } finally {
            cardinalityLock.unlockWrite(stamp);
        }
        return new DimensionResult(endpoint, status, endpointOutcome, statusOutcome);
    }

    private boolean isValidEndpoint(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxEndpointLength) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '/' || c == '_' || c == '-'
                    || c == '{' || c == '}' || c == '*' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidStatus(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 32) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** 采集一致性快照：结构与计数在同一张乐观/读锁保护下读取。 */
    public MetricsSnapshot snapshot() {
        long stamp = cardinalityLock.readLock();
        try {
            List<MetricsSnapshot.SeriesSnapshot> snapshots = new ArrayList<>(seriesByKey.size());
            for (Series series : seriesByKey.values()) {
                snapshots.add(new MetricsSnapshot.SeriesSnapshot(
                        series.endpoint, series.status,
                        series.endpointCapped, series.statusCapped,
                        series.calls.get(), series.errorCalls.get(),
                        series.histogram.snapshot()));
            }
            return new MetricsSnapshot(
                    invalidDimensionRejections.get(), cappedDimensionMerges.get(),
                    maxEndpointValues * maxStatusValues, snapshots);
        } finally {
            cardinalityLock.unlockRead(stamp);
        }
    }

    /** 测试辅助：当前已接受的不同 endpoint 取值数量（常量桶不计入）。 */
    int distinctEndpoints() {
        return knownEndpoints.size();
    }

    /** 测试辅助：按 (endpoint,status) 精确查找序列，找不到返回 null。 */
    Series series(String endpoint, String status) {
        return seriesByKey.get(new SeriesKey(endpoint, status));
    }

    long invalidDimensionRejections() {
        return invalidDimensionRejections.get();
    }

    long cappedDimensionMerges() {
        return cappedDimensionMerges.get();
    }

    public record SeriesKey(String endpoint, String status) {
    }

    /** 维度解析结果：最终计入的序列键 + 两个维度各自的判定，便于区分拒绝原因。 */
    public record DimensionResult(SeriesKey key,
                                  DimensionOutcome endpointOutcome,
                                  DimensionOutcome statusOutcome) {
        public DimensionResult(String endpoint, String status,
                               DimensionOutcome endpointOutcome,
                               DimensionOutcome statusOutcome) {
            this(new SeriesKey(endpoint, status), endpointOutcome, statusOutcome);
        }
    }

    static final class Series {
        private final String endpoint;
        private final String status;
        private final boolean endpointCapped;
        private final boolean statusCapped;
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong errorCalls = new AtomicLong();
        private final LatencyHistogram histogram;

        Series(String endpoint, String status, boolean endpointCapped,
               boolean statusCapped, long[] histogramBounds) {
            this.endpoint = endpoint;
            this.status = status;
            this.endpointCapped = endpointCapped;
            this.statusCapped = statusCapped;
            this.histogram = new LatencyHistogram(histogramBounds);
        }

        void record(long latencyMillis, boolean error) {
            calls.incrementAndGet();
            if (error) {
                errorCalls.incrementAndGet();
            }
            histogram.record(latencyMillis);
        }

        public String endpoint() {
            return endpoint;
        }

        public String status() {
            return status;
        }

        public long calls() {
            return calls.get();
        }

        public long errorCalls() {
            return errorCalls.get();
        }
    }
}
