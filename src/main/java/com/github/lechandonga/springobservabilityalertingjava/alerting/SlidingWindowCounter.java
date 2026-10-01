package com.github.lechandonga.springobservabilityalertingjava.alerting;

/**
 * 按秒对齐的环形桶滑动窗口计数器。所有方法在实例锁内执行：
 * 并发记录与评估不会把样本写错桶，窗口和不会跨桶错位。
 */
final class SlidingWindowCounter {

    private final long[] buckets;
    private long lastTick;
    private long lastDataTick;

    SlidingWindowCounter(int windowSeconds, long originTick) {
        this.buckets = new long[windowSeconds];
        this.lastTick = originTick;
        this.lastDataTick = Long.MIN_VALUE;
    }

    synchronized void add(long amount, long tick) {
        advance(tick);
        buckets[(int) (tick % buckets.length)] += amount;
        lastDataTick = tick;
    }

    /** 把空桶滚动到 tick，并返回最近 windowSeconds 个桶（含 tick）的总和。 */
    synchronized long windowSum(long tick) {
        advance(tick);
        long sum = 0;
        for (long value : buckets) {
            sum += value;
        }
        return sum;
    }

    synchronized long lastDataTick() {
        return lastDataTick;
    }

    private void advance(long tick) {
        if (tick < lastTick) {
            throw new IllegalArgumentException("clock moved backwards: " + tick + " < " + lastTick);
        }
        long gap = tick - lastTick;
        if (gap >= buckets.length) {
            java.util.Arrays.fill(buckets, 0L);
        } else {
            for (long t = lastTick + 1; t <= tick; t++) {
                buckets[(int) (t % buckets.length)] = 0L;
            }
        }
        lastTick = tick;
    }
}
