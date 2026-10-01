package com.github.lechandonga.springobservabilityalertingjava.tests.metrics;

import com.github.lechandonga.springobservabilityalertingjava.metrics.DimensionSanitizer;
import com.github.lechandonga.springobservabilityalertingjava.metrics.RequestMetrics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestMetricsTest {

    private static final Logger log = LoggerFactory.getLogger(RequestMetricsTest.class);

    @Test
    void concurrentWritesLoseNoCountsAndHistogramStaysAligned() throws Exception {
        int threads = 16;
        int perThread = 5_000;
        RequestMetrics metrics = new RequestMetrics(1000);
        log.info("input: threads={} perThread={} expectedTotal={}", threads, perThread, threads * perThread);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                for (int i = 0; i < perThread; i++) {
                    metrics.record("GET /api/hello/{name}", "2xx", i % 300);
                }
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        long expected = (long) threads * perThread;
        long count = metrics.countOf("GET /api/hello/{name}", "2xx");
        var snapshot = metrics.snapshot().get("GET /api/hello/{name}|2xx");
        long histogramSum = 0;
        for (long b : snapshot.latency().bucketCounts()) {
            histogramSum += b;
        }
        log.info("decision basis: count={} histogramSum={} expected={}", count, histogramSum, expected);
        assertEquals(expected, count, "counter lost updates under concurrency");
        assertEquals(expected, histogramSum, "histogram buckets misaligned with count");
        assertEquals(expected, snapshot.count());
    }

    @Test
    void cardinalityOverflowMergesIntoDistinguishableBucket() {
        RequestMetrics metrics = new RequestMetrics(4);
        log.info("input: maxSeries=4, recording 6 distinct endpoints");
        for (int i = 0; i < 6; i++) {
            var key = metrics.record("GET /e" + i, "2xx", 1);
            log.info("record endpoint=GET /e{} -> series={}", i, key);
        }
        log.info("decision basis: seriesCount={} overflowed={}",
                metrics.seriesCount(), metrics.overflowedObservations());
        // 4 normal series (capped) + 1 distinguishable overflow series
        assertEquals(5, metrics.seriesCount(), "normal series capped, overflow bounded by status class");
        assertEquals(2, metrics.overflowedObservations(), "overflow must be accounted, not dropped");
        assertEquals(2, metrics.countOf(DimensionSanitizer.OVERFLOW_BUCKET, "2xx"));
    }

    @Test
    void illegalDimensionValuesMergeIntoInvalidBucket() {
        RequestMetrics metrics = new RequestMetrics(10);
        log.info("input: endpoint='GET /a b!c' (illegal chars), endpoint=null");
        var k1 = metrics.record("GET /a b!c", "2xx", 1);
        var k2 = metrics.record(null, "2xx", 1);
        log.info("decision basis: k1={} k2={} invalidCount={}", k1, k2, metrics.invalidDimensionObservations());
        assertEquals(DimensionSanitizer.INVALID_BUCKET, k1.endpoint());
        assertEquals(DimensionSanitizer.INVALID_BUCKET, k2.endpoint());
        assertEquals(2, metrics.invalidDimensionObservations());
        assertEquals(2, metrics.countOf(DimensionSanitizer.INVALID_BUCKET, "2xx"));
    }

    @Test
    void latencyLandsInExactlyOneBucket() {
        RequestMetrics metrics = new RequestMetrics(10);
        metrics.record("GET /x", "2xx", 7);
        var snapshot = metrics.snapshot().get("GET /x|2xx");
        long sum = 0;
        for (long b : snapshot.latency().bucketCounts()) {
            sum += b;
        }
        log.info("input: latency=7ms, decision basis: bucketSum={} avg={}", sum, snapshot.latency().avgMs());
        assertEquals(1, sum);
        assertTrue(snapshot.latency().avgMs() == 7.0);
    }
}
