package com.github.lechandonga.springobservabilityalertingjava.requestid;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 请求标识相关配置，前缀 {@code observability.request-id}。
 */
@ConfigurationProperties(prefix = "observability.request-id")
public record RequestIdProperties(
        String headerName) {

    public RequestIdProperties {
        if (headerName == null || headerName.isBlank()) {
            headerName = "X-Request-Id";
        }
    }

    /** 供 Spring 按无参构造绑定使用，默认头名 X-Request-Id。 */
    public RequestIdProperties() {
        this("X-Request-Id");
    }
}
