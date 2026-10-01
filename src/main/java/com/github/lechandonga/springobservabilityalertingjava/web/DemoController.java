package com.github.lechandonga.springobservabilityalertingjava.web;

import com.github.lechandonga.springobservabilityalertingjava.requestid.RequestIdFilter;
import com.github.lechandonga.springobservabilityalertingjava.requestid.RequestIdResolution;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 演示用接口：正常路径、404 通配以及一个必抛异常的路径，覆盖成功/失败两类指标。 */
@RestController
public class DemoController {

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        return Map.of("message", "ok");
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> user(@PathVariable String id) {
        return Map.of("userId", id);
    }

    @GetMapping("/boom")
    public Map<String, Object> boom(HttpServletRequest request) {
        RequestIdResolution resolution = (RequestIdResolution)
                request.getAttribute(RequestIdFilter.ATTR_RESOLUTION);
        throw new IllegalStateException("intentional failure for request "
                + (resolution == null ? "unknown" : resolution.requestId()));
    }
}
