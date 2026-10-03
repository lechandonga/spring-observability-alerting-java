package com.github.lechandonga.springobservabilityalertingjava.web;

import com.github.lechandonga.springobservabilityalertingjava.alert.RuleValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Logs the original exception (with stack trace and the MDC request id) and
 * returns a stable error body. The original error is never swallowed or
 * rewritten: the message is echoed and the stack trace is logged as-is.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Rejected rule changes are client errors with a machine-readable
     * {@code reason} so callers can distinguish a missing field from a
     * duplicate name, an unknown rule, or an exceeded limit.
     */
    @ExceptionHandler(RuleValidationException.class)
    public ResponseEntity<Map<String, Object>> handleRuleValidation(RuleValidationException ex) {
        HttpStatus status = switch (ex.reason()) {
            case RULE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_NAME, LIMIT_EXCEEDED -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        log.warn("rule change rejected: reason={} message={}", ex.reason(), ex.getMessage());
        return ResponseEntity.status(status).body(Map.of(
                "error", "RuleValidation",
                "reason", ex.reason().name(),
                "message", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex) {
        log.error("request failed: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", ex.getClass().getSimpleName(),
                "message", String.valueOf(ex.getMessage())));
    }
}
