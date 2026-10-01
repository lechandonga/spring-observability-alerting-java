package com.github.lechandonga.springobservabilityalertingjava.alerting;

/** 规则监控的指标口径：窗口内总调用量，或窗口内异常调用量。 */
public enum AlertMetric {
    CALLS,
    ERRORS
}
