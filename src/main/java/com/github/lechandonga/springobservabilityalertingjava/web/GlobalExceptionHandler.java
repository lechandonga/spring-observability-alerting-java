package com.github.lechandonga.springobservabilityalertingjava.web;

import com.github.lechandonga.springobservabilityalertingjava.alert.RuleManagementException;
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
     * Rejected rule-management change: the body carries a machine-readable
     * {@code code} so clients can distinguish validation errors (400),
     * missing rules (404) and name/limit conflicts (409).
     */
    @ExceptionHandler(RuleManagementException.class)
    public ResponseEntity<Map<String, Object>> handleRuleManagement(RuleManagementException ex) {
        HttpStatus status = switch (ex.code()) {
            case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case RULE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_RULE, RULE_LIMIT_EXCEEDED -> HttpStatus.CONFLICT;
        };
        log.warn("rule management rejected: code={} message={}", ex.code(), ex.getMessage());
        return ResponseEntity.status(status).body(Map.of(
                "error", ex.code().name(),
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
