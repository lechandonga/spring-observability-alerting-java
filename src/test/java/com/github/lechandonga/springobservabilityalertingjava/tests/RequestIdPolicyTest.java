package com.github.lechandonga.springobservabilityalertingjava.tests;

import com.github.lechandonga.springobservabilityalertingjava.requestid.RequestIdOrigin;
import com.github.lechandonga.springobservabilityalertingjava.requestid.RequestIdPolicy;
import com.github.lechandonga.springobservabilityalertingjava.requestid.RequestIdResolution;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求标识异常场景：缺失/合法透传/非法/重复四类输入的判定必须可区分，
 * 且生成的新标识格式固定；日志打印输入与判定依据。
 */
class RequestIdPolicyTest {

    private static final Logger log = LoggerFactory.getLogger(RequestIdPolicyTest.class);
    private final RequestIdPolicy policy = new RequestIdPolicy();

    @Test
    void missingInboundIsGenerated() {
        RequestIdResolution resolution = policy.resolve(List.of());
        log.info("input=[] origin={} resolved={}", resolution.origin(), resolution.requestId());
        assertEquals(RequestIdOrigin.GENERATED, resolution.origin());
        assertTrue(resolution.requestId().matches("req-[0-9a-f]{32}"));
    }

    @Test
    void singleValidIdIsTransparentlyAccepted() {
        RequestIdResolution resolution = policy.resolve(List.of("upstream-abc-123_XYZ"));
        log.info("input=[upstream-abc-123_XYZ] origin={} resolved={}", resolution.origin(),
                resolution.requestId());
        assertEquals(RequestIdOrigin.INBOUND_ACCEPTED, resolution.origin());
        assertEquals("upstream-abc-123_XYZ", resolution.requestId());
    }

    @Test
    void invalidIdIsRejectedWithDistinctReason() {
        for (String invalid : List.of("short", "bad space", "中文标识", "x".repeat(129), "", "   ")) {
            RequestIdResolution resolution = policy.resolve(List.of(invalid));
            log.info("input='{}' origin={} (expected INVALID_REJECTED) resolved={}",
                    invalid, resolution.origin(), resolution.requestId());
            assertEquals(RequestIdOrigin.INVALID_REJECTED, resolution.origin());
            assertEquals(List.of(invalid.trim()), resolution.inboundValues(),
                    "original raw value must be preserved for diagnostics");
            assertTrue(resolution.requestId().matches("req-[0-9a-f]{32}"));
        }
    }

    @Test
    void duplicatedIdsAreRejectedWithDistinctReason() {
        RequestIdResolution resolution = policy.resolve(List.of("id-aaaaaaaa", "id-bbbbbbbb"));
        log.info("input=[id-aaaaaaaa,id-bbbbbbbb] origin={} (expected DUPLICATE_REJECTED) inbound={}",
                resolution.origin(), resolution.inboundValues());
        assertEquals(RequestIdOrigin.DUPLICATE_REJECTED, resolution.origin());
        assertEquals(2, resolution.inboundValues().size());

        RequestIdResolution evenIdentical = policy.resolve(List.of("id-cccccccc", "id-cccccccc"));
        assertEquals(RequestIdOrigin.DUPLICATE_REJECTED, evenIdentical.origin());
        assertNotEquals("id-cccccccc", evenIdentical.requestId());
    }
}
