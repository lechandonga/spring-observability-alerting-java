package com.github.lechandonga.springobservabilityalertingjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 端到端冒烟：真实请求后指标端点能查到该接口序列（含 /boom 的异常计数）。 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityEndToEndTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requestsAreReflectedInMetricsSnapshot() throws Exception {
        mockMvc.perform(get("/hello")).andExpect(status().isOk());
        mockMvc.perform(get("/users/42")).andExpect(status().isOk());
        mockMvc.perform(get("/boom")).andExpect(status().isInternalServerError());

        MvcResult result = mockMvc.perform(get("/observability/metrics"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        String content = body.toString();
        log.info("metrics snapshot after hello/users/boom: {}", content);

        assertTrue(content.contains("/hello"));
        assertTrue(content.contains("/users/{id}"), "path template must be used as endpoint dimension");
        assertTrue(content.contains("/boom"));
        assertTrue(body.path("series").isArray());
    }
}
