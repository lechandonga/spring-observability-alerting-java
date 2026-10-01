package com.github.lechandonga.springobservabilityalertingjava.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Thread-safe fixed-bucket latency histogram (milliseconds).
 *
 * <p>All buckets are pre-allocated, so concurrent writes never resize or
 * reorder anything: each observation lands in exactly one bucket via
 * {@link AtomicLongArray#incrementAndGet}, and snapshots are a consistent
 * per-bucket read (counts are monotonic, so a snapshot is always a valid
 * prefix of the real distribution).
 */
public final class LatencyHistogram {

    /** Upper bounds (inclusive) in ms; the last slot is +Inf. */
    public static final long[] BUCKETS_MS = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};

    private final AtomicLongArray buckets = new AtomicLongArray(BUCKETS_MS.length + 1);
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong totalMs = new AtomicLong();

    public void record(long latencyMs) {
        if (latencyMs < 0) {
            latencyMs = 0;
        }
        buckets.incrementAndGet(bucketIndex(latencyMs));
        count.incrementAndGet();
        totalMs.addAndGet(latencyMs);
    }

    static int bucketIndex(long latencyMs) {
        for (int i = 0; i < BUCKETS_MS.length; i++) {
            if (latencyMs <= BUCKETS_MS[i]) {
                return i;
            }
        }
        return BUCKETS_MS.length;
    }

    public Snapshot snapshot() {
        long[] counts = new long[buckets.length()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = buckets.get(i);
        }
        return new Snapshot(counts, count.get(), totalMs.get());
    }

    public record Snapshot(long[] bucketCounts, long count, long totalMs) {
        public double avgMs() {
            return count == 0 ? 0.0 : (double) totalMs / count;
        }
    }
}
