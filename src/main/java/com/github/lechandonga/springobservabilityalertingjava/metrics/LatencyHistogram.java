package com.github.lechandonga.springobservabilityalertingjava.metrics;

import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;

/**
 * 固定上界的时延分布直方图（毫秒）。
 * 桶边界在构造时确定、永不扩张，从根本上排除了“分布桶无限增长/错位”的可能：
 * 任何样本只会落入唯一一个桶（或 underflow/overflow），记录与快照均在同一把
 * {@link StampedLock} 下完成，并发写入时不会出现计数丢失或桶间错位。
 */
public final class LatencyHistogram {

    private static final long NO_SAMPLE = -1L;

    private final long[] upperBounds;
    private final long[] bucketCounts;
    private final StampedLock lock = new StampedLock();

    private long count;
    private double sum;
    private long min = NO_SAMPLE;
    private long max = NO_SAMPLE;
    private long underflow;
    private long overflow;

    public LatencyHistogram(long[] upperBoundsMillis) {
        if (upperBoundsMillis.length == 0) {
            throw new IllegalArgumentException("histogram must have at least one bucket bound");
        }
        this.upperBounds = Arrays.copyOf(upperBoundsMillis, upperBoundsMillis.length);
        Arrays.sort(this.upperBounds);
        if (this.upperBounds[0] <= 0) {
            throw new IllegalArgumentException("histogram bucket bounds must be positive");
        }
        this.bucketCounts = new long[this.upperBounds.length];
    }

    /** 记录一个非负时延样本；负数样本视为非法输入抛出异常而非静默丢弃。 */
    public void record(long latencyMillis) {
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("latency must be non-negative but was " + latencyMillis);
        }
        long stamp = lock.writeLock();
        try {
            count++;
            sum += latencyMillis;
            min = (min == NO_SAMPLE) ? latencyMillis : Math.min(min, latencyMillis);
            max = (max == NO_SAMPLE) ? latencyMillis : Math.max(max, latencyMillis);
            int idx = Arrays.binarySearch(upperBounds, latencyMillis);
            if (idx < 0) {
                int insertion = -idx - 1;
                if (insertion == 0) {
                    underflow++;
                } else if (insertion == upperBounds.length) {
                    overflow++;
                } else {
                    bucketCounts[insertion]++;
                }
            } else {
                bucketCounts[idx]++;
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /** 读取一致性快照：count/sum/min/max 与桶计数来自同一临界区。 */
    public HistogramSnapshot snapshot() {
        long stamp = lock.readLock();
        try {
            return new HistogramSnapshot(
                    count, sum,
                    min == NO_SAMPLE ? 0 : min,
                    max == NO_SAMPLE ? 0 : max,
                    underflow, overflow,
                    Arrays.copyOf(bucketCounts, bucketCounts.length),
                    Arrays.copyOf(upperBounds, upperBounds.length));
        } finally {
            lock.unlockRead(stamp);
        }
    }
}
