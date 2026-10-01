package com.github.lechandonga.springobservabilityalertingjava.metrics;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, thread-safe registry of per-series request counters and latency
 * histograms.
 *
 * <p>Dimension cardinality is capped by {@code maxSeries} for normal series.
 * When the cap is reached, observations for unknown keys are merged into the
 * distinguishable {@value DimensionSanitizer#OVERFLOW_BUCKET} series (one per
 * status class, hence bounded by the small status-class cardinality) and
 * accounted in {@link #overflowedObservations()} — never silently dropped,
 * never unbounded.
 */
public final class RequestMetrics {

    private final int maxSeries;
    private final ConcurrentHashMap<SeriesKey, Series> series = new ConcurrentHashMap<>();
    private final LongAdder overflowedObservations = new LongAdder();
    private final LongAdder invalidDimensionObservations = new LongAdder();

    public RequestMetrics(int maxSeries) {
        if (maxSeries < 1) {
            throw new IllegalArgumentException("maxSeries must be >= 1");
        }
        this.maxSeries = maxSeries;
    }

    /**
     * Records one request observation.
     *
     * @return the key actually written, so callers can tell merges apart.
     */
    public SeriesKey record(String endpoint, String statusClass, long latencyMs) {
        if (!DimensionSanitizer.isValid(endpoint) || !DimensionSanitizer.isValid(statusClass)) {
            invalidDimensionObservations.increment();
        }
        SeriesKey key = new SeriesKey(endpoint, statusClass);
        Series s = series.get(key);
        if (s == null) {
            if (series.size() >= maxSeries && !isOverflowKey(key)) {
                overflowedObservations.increment();
                key = new SeriesKey(DimensionSanitizer.OVERFLOW_BUCKET, key.statusClass());
            }
            s = series.computeIfAbsent(key, k -> new Series());
        }
        s.count.increment();
        s.histogram.record(latencyMs);
        return key;
    }

    private static boolean isOverflowKey(SeriesKey key) {
        return DimensionSanitizer.OVERFLOW_BUCKET.equals(key.endpoint());
    }

    public long countOf(String endpoint, String statusClass) {
        Series s = series.get(new SeriesKey(endpoint, statusClass));
        return s == null ? 0 : s.count.sum();
    }

    public long overflowedObservations() {
        return overflowedObservations.sum();
    }

    public long invalidDimensionObservations() {
        return invalidDimensionObservations.sum();
    }

    public int seriesCount() {
        return series.size();
    }

    /** Consistent-enough point-in-time view: per-series counts are exact, monotonic values. */
    public Map<String, SeriesSnapshot> snapshot() {
        Map<String, SeriesSnapshot> out = new TreeMap<>();
        series.forEach((key, s) -> out.put(key.toString(),
                new SeriesSnapshot(s.count.sum(), s.histogram.snapshot())));
        return out;
    }

    private static final class Series {
        final LongAdder count = new LongAdder();
        final LatencyHistogram histogram = new LatencyHistogram();
    }

    public record SeriesSnapshot(long count, LatencyHistogram.Snapshot latency) {
    }
}
