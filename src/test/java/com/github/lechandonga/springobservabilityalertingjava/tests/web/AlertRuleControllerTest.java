package com.github.lechandonga.springobservabilityalertingjava.tests.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRuleManager;
import com.github.lechandonga.springobservabilityalertingjava.web.AlertRuleController;
import com.github.lechandonga.springobservabilityalertingjava.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertRuleControllerTest {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleControllerTest.class);

    private MockMvc mvc;
    private AlertEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AlertEngine(Clock.systemUTC(), Duration.ofMinutes(10));
        AlertRuleManager manager = new AlertRuleManager(engine, 2);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(mapper);
        mvc = MockMvcBuilders
                .standaloneSetup(new AlertRuleController(manager, engine))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    private static String ruleJson(String name, int threshold) {
        return """
                {"name":"%s","endpoint":"GET /api/fail","statusClass":"5xx",
                 "threshold":%d,"window":"PT1M","cooldown":"PT2M"}
                """.formatted(name, threshold);
    }

    @Test
    void addListUpdateDeleteRoundTrip() throws Exception {
        mvc.perform(post("/observability/rules").contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson("errors", 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("errors"));
        log.info("decision basis: rules after add={}", engine.currentRules());

        mvc.perform(get("/observability/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRules").value(2))
                .andExpect(jsonPath("$.rules[0].name").value("errors"));

        mvc.perform(put("/observability/rules/errors").contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson("ignored", 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threshold").value(5));

        mvc.perform(get("/observability/rules/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleName").value("errors"))
                .andExpect(jsonPath("$[0].recovered").value(true));

        mvc.perform(delete("/observability/rules/errors"))
                .andExpect(status().isOk());
        log.info("decision basis: rules after delete={}", engine.currentRules());
        mvc.perform(get("/observability/rules"))
                .andExpect(jsonPath("$.rules.length()").value(0));
    }

    @Test
    void errorResponsesDistinguishReasons() throws Exception {
        mvc.perform(post("/observability/rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"GET /x\",\"statusClass\":\"5xx\","
                                + "\"threshold\":1,\"window\":\"PT1M\",\"cooldown\":\"PT0S\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("MISSING_FIELD"));

        mvc.perform(post("/observability/rules").contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson("a", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_VALUE"));

        mvc.perform(post("/observability/rules").contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson("a", 1)));
        mvc.perform(post("/observability/rules").contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson("a", 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("DUPLICATE_NAME"));

        mvc.perform(post("/observability/rules").contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson("b", 1)));
        mvc.perform(post("/observability/rules").contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson("c", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("LIMIT_EXCEEDED"));

        mvc.perform(put("/observability/rules/ghost").contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson("ghost", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("RULE_NOT_FOUND"));
        mvc.perform(delete("/observability/rules/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("RULE_NOT_FOUND"));

        log.info("decision basis: rules after rejections={}", engine.currentRules());
        mvc.perform(get("/observability/rules"))
                .andExpect(jsonPath("$.rules.length()").value(2));
    }
}
