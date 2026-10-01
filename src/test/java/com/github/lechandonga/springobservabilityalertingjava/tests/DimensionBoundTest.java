package com.github.lechandonga.springobservabilityalertingjava.tests;

import com.github.lechandonga.springobservabilityalertingjava.metrics.DimensionOutcome;
import com.github.lechandonga.springobservabilityalertingjava.metrics.MetricsRegistry;
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
 * 维度上限/非法值场景：验证 INVALID 与 CAPPED 两种拒绝结论可区分，
 * 且并发冲击上限时被接受的不同值数量恰好等于上限（不多不少）。
 */
class DimensionBoundTest {

    private static final Logger log = LoggerFactory.getLogger(DimensionBoundTest.class);

    @Test
    void invalidAndCappedDimensionsAreDistinguishable() {
        MetricsRegistry registry = new MetricsRegistry(3, 2, 16, new long[]{10, 50});

        log.info("input: 3 valid endpoints within limit");
        registry.record("/a", "200", 1, false);
        registry.record("/b", "200", 2, false);
        registry.record("/c", "200", 3, false);

        MetricsRegistry.DimensionResult capped = registry.record("/fourth-new-path", "200", 4, false);
        log.info("input=/fourth-new-path decision endpoint={} (expected CAPPED), status={}",
                capped.endpointOutcome(), capped.statusOutcome());
        assertEquals(DimensionOutcome.CAPPED, capped.endpointOutcome());
        assertEquals(MetricsRegistry.CAPPED_BUCKET, capped.key().endpoint());

        MetricsRegistry.DimensionResult invalidChars = registry.record("/bad path!", "200", 5, false);
        log.info("input='/bad path!' decision endpoint={} (expected INVALID)", invalidChars.endpointOutcome());
        assertEquals(DimensionOutcome.INVALID, invalidChars.endpointOutcome());
        assertEquals(MetricsRegistry.INVALID_BUCKET, invalidChars.key().endpoint());

        MetricsRegistry.DimensionResult tooLong = registry.record("x".repeat(17), "200", 6, false);
        assertEquals(DimensionOutcome.INVALID, tooLong.endpointOutcome());

        MetricsRegistry.DimensionResult badStatus = registry.record("/a", "weird status", 7, true);
        log.info("input status='weird status' decision status={} (expected INVALID)",
                badStatus.statusOutcome());
        assertEquals(DimensionOutcome.INVALID, badStatus.statusOutcome());

        MetricsRegistry.DimensionResult acceptedAgain = registry.record("/a", "500", 8, true);
        assertEquals(DimensionOutcome.ACCEPTED, acceptedAgain.statusOutcome());

        MetricsRegistry.DimensionResult statusCapped = registry.record("/a", "503", 9, true);
        log.info("input status=503 (200/500 already accepted, limit=2) decision status={} (expected CAPPED)",
                statusCapped.statusOutcome());
        assertEquals(DimensionOutcome.CAPPED, statusCapped.statusOutcome());
        assertEquals(MetricsRegistry.CAPPED_BUCKET, statusCapped.key().status());

        assertEquals(3L, registry.snapshot().invalidDimensionRejections());
        assertEquals(2L, registry.snapshot().cappedDimensionMerges());
        log.info("rejection counters invalid={} capped={} (expected 3/2)",
                registry.snapshot().invalidDimensionRejections(),
                registry.snapshot().cappedDimensionMerges());
    }

    @Test
    void concurrentRushAcceptsExactlyTheLimit() throws InterruptedException {
        int limit = 10;
        int rushers = 100;
        MetricsRegistry registry = new MetricsRegistry(limit, 2, 64, new long[]{10});

        ExecutorService pool = Executors.newFixedThreadPool(rushers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(rushers);
        for (int i = 0; i < rushers; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    registry.record("/ep-" + index, "200", 1, false);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        int distinct = registry.snapshot().series().stream()
                .mapToInt(series -> series.endpoint().startsWith("/ep-") ? 1 : 0)
                .sum();
        long merged = registry.snapshot().cappedDimensionMerges();
        log.info("input rushers={} limit={} acceptedDistinct={} cappedMerges={} (accepted+merged={})",
                rushers, limit, distinct, merged, distinct + merged);
        assertEquals(limit, distinct, "exactly the cardinality limit must be accepted");
        assertEquals(rushers - limit, merged, "every overflowing value must be merged, none dropped");
    }
}
