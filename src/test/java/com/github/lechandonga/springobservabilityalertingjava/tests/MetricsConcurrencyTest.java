package com.github.lechandonga.springobservabilityalertingjava.tests;

import com.github.lechandonga.springobservabilityalertingjava.metrics.HistogramSnapshot;
import com.github.lechandonga.springobservabilityalertingjava.metrics.MetricsRegistry;
import com.github.lechandonga.springobservabilityalertingjava.metrics.MetricsSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发写入场景：多线程同时累计同一组序列，验证
 * 1) 调用量不丢；2) 直方图桶计数之和 == 样本总数（不错位）；
 * 3) 快照中各字段自洽。
 */
class MetricsConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsConcurrencyTest.class);

    private static final int THREADS = 16;
    private static final int ITERATIONS_PER_THREAD = 5_000;

    @Test
    void concurrentRecordsDoNotLoseCountsOrMisplaceBuckets() throws InterruptedException {
        MetricsRegistry registry = new MetricsRegistry(8, 4, 128,
                new long[]{5, 10, 25, 50, 100});

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int worker = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                        String endpoint = "/api/e" + (i % 4);
                        String status = (worker + i) % 5 == 0 ? "500" : "200";
                        long latency = i % 120L;
                        registry.record(endpoint, status, latency, "500".equals(status));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        long begin = System.nanoTime();
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "workers must finish");
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000L;
        pool.shutdownNow();

        MetricsSnapshot snapshot = registry.snapshot();
        long totalCalls = snapshot.series().stream()
                .mapToLong(MetricsSnapshot.SeriesSnapshot::calls).sum();
        long totalErrors = snapshot.series().stream()
                .mapToLong(MetricsSnapshot.SeriesSnapshot::errorCalls).sum();

        long expectedTotal = (long) THREADS * ITERATIONS_PER_THREAD;
        long expectedErrors = 0;
        for (int t = 0; t < THREADS; t++) {
            for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                if ((t + i) % 5 == 0) {
                    expectedErrors++;
                }
            }
        }

        for (MetricsSnapshot.SeriesSnapshot series : snapshot.series()) {
            HistogramSnapshot histogram = series.latency();
            long bucketSum = histogram.underflow() + histogram.overflow();
            for (long bucketCount : histogram.bucketCounts()) {
                bucketSum += bucketCount;
            }
            log.info("series endpoint={} status={} input(calls={}) check(calls={}, histogramCount={}, bucketSum={})",
                    series.endpoint(), series.status(), series.calls(),
                    histogram.count(), histogram.count(), bucketSum);
            assertEquals(series.calls(), histogram.count(),
                    "calls and histogram count must agree for " + series.endpoint());
            assertEquals(histogram.count(), bucketSum,
                    "every sample must land in exactly one bucket for " + series.endpoint());
            assertTrue(histogram.maxMillis() >= histogram.minMillis(),
                    "snapshot min/max must be self-consistent");
        }

        log.info("concurrency input threads={} perThread={} expectedTotal={} expectedErrors={} elapsedMillis={}",
                THREADS, ITERATIONS_PER_THREAD, expectedTotal, expectedErrors, elapsedMillis);
        assertEquals(expectedTotal, totalCalls, "no call may be lost under concurrency");
        assertEquals(expectedErrors, totalErrors, "error accounting must be exact");
        assertEquals(0L, registry.snapshot().invalidDimensionRejections());
        assertEquals(0L, registry.snapshot().cappedDimensionMerges());
    }
}
