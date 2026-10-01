package com.github.lechandonga.springobservabilityalertingjava.requestid;

/**
 * 本次请求标识的来源判定，四种情况必须可区分：
 * <ul>
 *   <li>{@link #INBOUND_ACCEPTED}：上游恰好带了 1 个合法标识，原样透传。</li>
 *   <li>{@link #GENERATED}：上游未带标识，由本服务生成。</li>
 *   <li>{@link #INVALID_REJECTED}：上游标识非法（字符/长度不符），拒绝并重新生成。</li>
 *   <li>{@link #DUPLICATE_REJECTED}：上游带了多个标识或同名重复头，拒绝并重新生成。</li>
 * </ul>
 */
public enum RequestIdOrigin {
    INBOUND_ACCEPTED,
    GENERATED,
    INVALID_REJECTED,
    DUPLICATE_REJECTED
}
