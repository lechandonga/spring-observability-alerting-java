package com.github.lechandonga.springobservabilityalertingjava.metrics;

/**
 * 时延直方图的不可变快照。所有字段来自同一次加锁读取，因此彼此自洽：
 * 各桶计数之和 + underflow + overflow == count，且 sum/min/max 覆盖同一批样本。
 */
public record HistogramSnapshot(
        long count,
        double sum,
        long minMillis,
        long maxMillis,
        long underflow,
        long overflow,
        long[] bucketCounts,
        long[] upperBoundsMillis) {

    /** 取某个上界桶（含该上界）的累计样本数，index 越界返回 -1 以便测试区分调用错误。 */
    public long bucketCountAt(int index) {
        if (index < 0 || index >= bucketCounts.length) {
            return -1;
        }
        return bucketCounts[index];
    }
}
