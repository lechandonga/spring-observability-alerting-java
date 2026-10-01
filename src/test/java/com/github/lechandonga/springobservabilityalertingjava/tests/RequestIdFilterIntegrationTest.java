package com.github.lechandonga.springobservabilityalertingjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.springobservabilityalertingjava.requestid.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 端到端验证：响应头回写标识、异常路径原始异常未被吞掉（500 + 自定义消息）。 */
@SpringBootTest
@AutoConfigureMockMvc
class RequestIdFilterIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilterIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generatedIdIsReturnedInResponseHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/hello"))
                .andExpect(status().isOk())
                .andReturn();
        String requestId = result.getResponse().getHeader("X-Request-Id");
        log.info("input no-header path=/hello responseHeader X-Request-Id={}", requestId);
        assertNotNull(requestId);
        assertTrue(requestId.matches("req-[0-9a-f]{32}"));
    }

    @Test
    void validInboundIdIsEchoed() throws Exception {
        MvcResult result = mockMvc.perform(get("/hello").header("X-Request-Id", "trace-0001-abcd"))
                .andExpect(status().isOk())
                .andReturn();
        String echoed = result.getResponse().getHeader("X-Request-Id");
        log.info("input X-Request-Id=trace-0001-abcd responseHeader={}", echoed);
        assertEquals("trace-0001-abcd", echoed);
    }

    @Test
    void duplicateHeadersAreRejectedButResponseStillCarriesNewId() throws Exception {
        MvcResult result = mockMvc.perform(get("/hello")
                        .header("X-Request-Id", "id-aaaaaaaa")
                        .header("X-Request-Id", "id-bbbbbbbb"))
                .andExpect(status().isOk())
                .andReturn();
        String echoed = result.getResponse().getHeader("X-Request-Id");
        log.info("input duplicated headers responseHeader={}", echoed);
        assertNotNull(echoed);
        assertTrue(echoed.matches("req-[0-9a-f]{32}"));
    }

    @Test
    void errorPathPreservesOriginalExceptionAndRequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/boom").header("X-Request-Id", "err-0001-aaaa"))
                .andExpect(status().isInternalServerError())
                .andReturn();
        String echoed = result.getResponse().getHeader("X-Request-Id");
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        log.info("input path=/boom X-Request-Id=err-0001-aaaa responseHeader={} body.error={} body.message={}",
                echoed, body.path("error").asText(), body.path("message").asText());
        assertEquals("err-0001-aaaa", echoed, "request id must survive the error path");
        assertEquals(IllegalStateException.class.getName(), body.path("error").asText(),
                "original exception type must be preserved, not swallowed or rewritten");
        assertTrue(body.path("message").asText().contains("err-0001-aaaa"),
                "original error message must not be rewritten");
    }
}
