package com.github.lechandonga.springobservabilityalertingjava.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Demo endpoints to exercise the observability pipeline locally.
 */
@RestController
public class DemoController {

    @GetMapping("/api/hello/{name}")
    public Map<String, Object> hello(@PathVariable String name, HttpServletRequest request) {
        return Map.of(
                "message", "hello " + name,
                "requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    @GetMapping("/api/fail")
    public Map<String, Object> fail(@RequestParam(defaultValue = "boom") String reason) {
        throw new IllegalStateException("intentional failure: " + reason);
    }
}
