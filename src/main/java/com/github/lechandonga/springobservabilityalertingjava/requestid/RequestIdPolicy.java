package com.github.lechandonga.springobservabilityalertingjava.requestid;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求标识的确定性解析规则（无状态、可单测、结论可复现）：
 * <ol>
 *   <li>没有任何取值：生成 {@code req-<32位十六进制>}，来源 GENERATED。</li>
 *   <li>恰好 1 个取值且匹配 {@code [A-Za-z0-9._-]{8,128}}：原样接受。</li>
 *   <li>取值数量大于 1（重复头/逗号多值）：全部拒绝，重新生成，来源 DUPLICATE_REJECTED。</li>
 *   <li>唯一取值非法（含空白、非法字符、长度越界）：拒绝并重新生成，来源 INVALID_REJECTED。</li>
 * </ol>
 * 重复与非法的处理不同，且原始取值会随日志打印，不会被静默吞掉。
 */
public final class RequestIdPolicy {

    public static final String VALID_PATTERN_SOURCE = "[A-Za-z0-9._-]{8,128}";

    private static final Pattern VALID = Pattern.compile(VALID_PATTERN_SOURCE);

    /** 解析上游原始取值列表；传入 null/空列表等价于“未携带”。 */
    public RequestIdResolution resolve(List<String> inboundValues) {
        List<String> values = inboundValues == null ? List.of() : inboundValues.stream()
                .map(value -> value == null ? "" : value.trim())
                .toList();
        if (values.isEmpty()) {
            return new RequestIdResolution(generate(), RequestIdOrigin.GENERATED, List.of());
        }
        if (values.size() > 1) {
            return new RequestIdResolution(generate(), RequestIdOrigin.DUPLICATE_REJECTED, values);
        }
        String only = values.get(0);
        if (!VALID.matcher(only).matches()) {
            return new RequestIdResolution(generate(), RequestIdOrigin.INVALID_REJECTED, values);
        }
        return new RequestIdResolution(only, RequestIdOrigin.INBOUND_ACCEPTED, values);
    }

    /** 生成规则固定前缀 + UUID 去横线，保证合法且全局唯一可重现其格式。 */
    public static String generate() {
        return "req-" + UUID.randomUUID().toString().replace("-", "");
    }
}
