package com.github.lechandonga.springobservabilityalertingjava.requestid;

import java.util.List;

/**
 * 请求标识解析结果（不可变）。{@code requestId} 永远非空，
 * {@code inboundValues} 保留上游原始取值（可能为空），便于日志还原现场。
 */
public record RequestIdResolution(
        String requestId,
        RequestIdOrigin origin,
        List<String> inboundValues) {
}
