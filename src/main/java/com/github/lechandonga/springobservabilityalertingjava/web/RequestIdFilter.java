package com.github.lechandonga.springobservabilityalertingjava.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Generates / propagates the request id and binds it to logs via MDC.
 *
 * <p>Deterministic rules for the inbound {@code X-Request-Id} header:
 * absent/blank -&gt; generate (source=generated); duplicated header -&gt;
 * regenerate (source=regenerated-duplicate); not matching
 * {@code [A-Za-z0-9\-]{8,64}} -&gt; regenerate (source=regenerated-invalid);
 * otherwise propagate (source=propagated). The effective id is echoed in the
 * {@code X-Request-Id} response header and bound to MDC for the whole chain,
 * including error paths.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String SOURCE_HEADER = "X-Request-Id-Source";
    public static final String MDC_KEY = "requestId";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9\\-]{8,64}");
    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Resolution resolution = resolve(request);
        request.setAttribute(ATTRIBUTE, resolution.id());
        response.setHeader(HEADER, resolution.id());
        response.setHeader(SOURCE_HEADER, resolution.source());
        MDC.put(MDC_KEY, resolution.id());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private Resolution resolve(HttpServletRequest request) {
        var headers = request.getHeaders(HEADER);
        if (!headers.hasMoreElements()) {
            return new Resolution(newId(), "generated");
        }
        String first = headers.nextElement();
        if (headers.hasMoreElements()) {
            log.info("duplicate {} headers, regenerating request id", HEADER);
            return new Resolution(newId(), "regenerated-duplicate");
        }
        if (first == null || first.isBlank()) {
            return new Resolution(newId(), "generated");
        }
        if (!VALID_ID.matcher(first).matches()) {
            log.info("invalid {} value, regenerating request id", HEADER);
            return new Resolution(newId(), "regenerated-invalid");
        }
        return new Resolution(first, "propagated");
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private record Resolution(String id, String source) {
    }
}
