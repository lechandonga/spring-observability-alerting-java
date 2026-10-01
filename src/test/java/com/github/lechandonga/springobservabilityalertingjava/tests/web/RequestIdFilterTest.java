package com.github.lechandonga.springobservabilityalertingjava.tests.web;

import com.github.lechandonga.springobservabilityalertingjava.web.RequestIdFilter;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RequestIdFilterTest {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilterTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingIdIsGeneratedAndEchoed() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/hello/world"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andReturn();
        String id = result.getResponse().getHeader(RequestIdFilter.HEADER);
        String source = result.getResponse().getHeader(RequestIdFilter.SOURCE_HEADER);
        log.info("input: no X-Request-Id header; decision basis: id={} source={}", id, source);
        assertEquals("generated", source);
        assertEquals(id, com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.requestId"));
    }

    @Test
    void validIdIsPropagated() throws Exception {
        mockMvc.perform(get("/api/hello/world").header(RequestIdFilter.HEADER, "upstream-abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, "upstream-abc-123"))
                .andExpect(header().string(RequestIdFilter.SOURCE_HEADER, "propagated"))
                .andExpect(jsonPath("$.requestId").value("upstream-abc-123"));
        log.info("input: X-Request-Id=upstream-abc-123; decision basis: source=propagated");
    }

    @Test
    void invalidIdIsRegeneratedAndDistinguishable() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/hello/world").header(RequestIdFilter.HEADER, "bad id!!"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.SOURCE_HEADER, "regenerated-invalid"))
                .andReturn();
        String id = result.getResponse().getHeader(RequestIdFilter.HEADER);
        log.info("input: X-Request-Id='bad id!!'; decision basis: regenerated id={}", id);
        assertNotNull(id);
        assertTrue(!id.equals("bad id!!"));
    }

    @Test
    void duplicateIdHeadersAreRegeneratedAndDistinguishable() throws Exception {
        mockMvc.perform(get("/api/hello/world")
                        .header(RequestIdFilter.HEADER, "id-one-aaa", "id-two-bbb"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.SOURCE_HEADER, "regenerated-duplicate"));
        log.info("input: two X-Request-Id headers; decision basis: source=regenerated-duplicate");
    }

    @Test
    void errorPathKeepsOriginalErrorAndExposesRequestId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/fail").param("reason", "db-down"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.error").value("IllegalStateException"))
                .andExpect(jsonPath("$.message").value("intentional failure: db-down"))
                .andReturn();
        log.info("input: GET /api/fail?reason=db-down; decision basis: status={} id={} body={}",
                result.getResponse().getStatus(),
                result.getResponse().getHeader(RequestIdFilter.HEADER),
                result.getResponse().getContentAsString());
    }
}
