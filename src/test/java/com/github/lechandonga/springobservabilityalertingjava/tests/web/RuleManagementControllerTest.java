package com.github.lechandonga.springobservabilityalertingjava.tests.web;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RuleManagementControllerTest {

    private static final Logger log = LoggerFactory.getLogger(RuleManagementControllerTest.class);

    @Autowired
    private MockMvc mockMvc;

    private static String ruleJson(String name, int threshold) {
        return """
                {"name":"%s","endpoint":"GET /api/fail","statusClass":"5xx",
                 "threshold":%d,"window":"PT1M","cooldown":"PT2M"}
                """.formatted(name, threshold);
    }

    @Test
    void crudLifecycleAndDistinctErrorSemantics() throws Exception {
        // configured rule from application.properties is loaded at startup
        MvcResult listed = mockMvc.perform(get("/observability/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRules").value(100))
                .andReturn();
        log.info("input: GET rules; decision basis: body={}", listed.getResponse().getContentAsString());
        assertTrue(listed.getResponse().getContentAsString().contains("fail-endpoint-errors"));

        mockMvc.perform(post("/observability/rules")
                        .contentType(MediaType.APPLICATION_JSON).content(ruleJson("rt-errors", 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("rt-errors"));

        MvcResult duplicate = mockMvc.perform(post("/observability/rules")
                        .contentType(MediaType.APPLICATION_JSON).content(ruleJson("rt-errors", 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_RULE"))
                .andReturn();
        log.info("input: duplicate add; decision basis: body={}", duplicate.getResponse().getContentAsString());

        mockMvc.perform(post("/observability/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"threshold\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        mockMvc.perform(put("/observability/rules/rt-errors")
                        .contentType(MediaType.APPLICATION_JSON).content(ruleJson("rt-errors", 7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threshold").value(7));

        mockMvc.perform(put("/observability/rules/ghost")
                        .contentType(MediaType.APPLICATION_JSON).content(ruleJson("ghost", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RULE_NOT_FOUND"));

        mockMvc.perform(delete("/observability/rules/rt-errors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value("rt-errors"));
        mockMvc.perform(delete("/observability/rules/rt-errors"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RULE_NOT_FOUND"));

        MvcResult after = mockMvc.perform(get("/observability/rules"))
                .andExpect(status().isOk())
                .andReturn();
        log.info("decision basis: rules after delete={}", after.getResponse().getContentAsString());
        assertTrue(!after.getResponse().getContentAsString().contains("rt-errors"),
                "deleted rule must be gone from the listing");
    }

    @Test
    void statusViewIsExposed() throws Exception {
        MvcResult result = mockMvc.perform(get("/observability/rules/status"))
                .andExpect(status().isOk())
                .andReturn();
        log.info("input: GET rules/status; decision basis: body={}", result.getResponse().getContentAsString());
    }
}
