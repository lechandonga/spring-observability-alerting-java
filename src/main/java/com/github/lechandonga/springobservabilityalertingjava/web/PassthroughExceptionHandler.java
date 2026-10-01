package com.github.lechandonga.springobservabilityalertingjava.web;

import com.github.lechandonga.springobservabilityalertingjava.requestid.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 兜底异常处理：把未处理异常转成 500 JSON，同时原样保留异常类型与消息。
 * 过滤器层仍会记录并（对过滤器链外的场景）重新抛出，原始错误不会被吞掉或改写。
 */
@RestControllerAdvice
public class PassthroughExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put("error", ex.getClass().getName());
        body.put("message", ex.getMessage());
        body.put("path", request.getRequestURI());
        body.put("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
