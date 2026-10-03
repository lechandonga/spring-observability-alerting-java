package com.github.lechandonga.springobservabilityalertingjava.web;

import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRule;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRuleManager;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleDefinition;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Runtime alert-rule management, next to the read-only observability views.
 * Changes apply atomically to the very next evaluation pass; they are not
 * persisted, so a restart falls back to the configured rules.
 */
@RestController
@RequestMapping("/observability/rules")
public class AlertRuleController {

    private final AlertRuleManager manager;
    private final AlertEngine engine;

    public AlertRuleController(AlertRuleManager manager, AlertEngine engine) {
        this.manager = manager;
        this.engine = engine;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of(
                "maxRules", manager.maxRules(),
                "rules", manager.list());
    }

    @GetMapping("/status")
    public List<RuleStatus> status() {
        return engine.ruleStatuses();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRule add(@RequestBody RuleDefinition definition) {
        return manager.add(definition);
    }

    @PutMapping("/{name}")
    public AlertRule update(@PathVariable String name, @RequestBody RuleDefinition definition) {
        return manager.update(name, definition);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        manager.delete(name);
        return Map.of("deleted", name);
    }
}
