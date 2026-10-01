package com.github.lechandonga.springobservabilityalertingjava.requestid;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 在最外层完成请求标识的解析、MDC 关联与响应回写。
 * <ul>
 *   <li>响应头在进入过滤链之前写入，因此即使下游抛异常，响应里仍能取到本次标识。</li>
 *   <li>异常只被记录（带 requestId 与原始消息/类型），随后原样抛出，不吞不改写。</li>
 *   <li>MDC 在 finally 中恢复为进入前的值，避免线程复用导致标识串请求。</li>
 * </ul>
 */
@Component
@Order(1)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    public static final String ATTR_RESOLUTION = RequestIdFilter.class.getName() + ".RESOLUTION";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    private final RequestIdPolicy policy;
    private final String headerName;

    public RequestIdFilter(RequestIdPolicy policy, RequestIdProperties properties) {
        this.policy = policy;
        this.headerName = properties.headerName();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        RequestIdResolution resolution = policy.resolve(collectInbound(request));
        request.setAttribute(ATTR_RESOLUTION, resolution);

        if (resolution.origin() != RequestIdOrigin.INBOUND_ACCEPTED) {
            log.warn("request id not transparently accepted, origin={}, inbound={}, resolved={}, path={}",
                    resolution.origin(), resolution.inboundValues(),
                    resolution.requestId(), request.getRequestURI());
        } else {
            log.info("request id accepted from inbound, requestId={}, path={}",
                    resolution.requestId(), request.getRequestURI());
        }

        response.setHeader(headerName, resolution.requestId());

        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, resolution.requestId());
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            log.error("request failed, requestId={}, path={}, errorType={}, message={}",
                    resolution.requestId(), request.getRequestURI(),
                    ex.getClass().getName(), ex.getMessage(), ex);
            throw ex;
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    /** 收集同名头的所有取值；只要出现多个值（哪怕内容相同）即判定为重复。 */
    private List<String> collectInbound(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(headerName);
        if (headers == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        boolean duplicatedHeaderName = false;
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            if (header == null) {
                continue;
            }
            String[] parts = header.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                values.add(trimmed);
            }
            duplicatedHeaderName = true;
        }
        if (!duplicatedHeaderName) {
            return Collections.emptyList();
        }
        return values;
    }

    public String headerName() {
        return headerName;
    }
}
