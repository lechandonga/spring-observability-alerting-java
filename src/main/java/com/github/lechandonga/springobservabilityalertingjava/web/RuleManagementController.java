package com.github.lechandonga.springobservabilityalertingjava.web;

import com.github.lechandonga.springobservabilityalertingjava.alert.AlertEngine;
import com.github.lechandonga.springobservabilityalertingjava.alert.AlertRule;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleManager;
import com.github.lechandonga.springobservabilityalertingjava.alert.RuleStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Runtime alert-rule management, next to the read-only observability views.
 * Changes take effect for the very next evaluation pass and are in-memory
 * only (restart restores the configured rules).
 */
@RestController
@RequestMapping("/observability/rules")
public class RuleManagementController {

    private final RuleManager ruleManager;
    private final AlertEngine alertEngine;

    public RuleManagementController(RuleManager ruleManager, AlertEngine alertEngine) {
        this.ruleManager = ruleManager;
        this.alertEngine = alertEngine;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of(
                "maxRules", ruleManager.maxRules(),
                "count", ruleManager.list().size(),
                "rules", ruleManager.list());
    }

    @PostMapping
    public ResponseEntity<AlertRule> add(@RequestBody RuleManager.RuleSpec spec) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleManager.add(spec));
    }

    @PutMapping("/{name}")
    public AlertRule update(@PathVariable String name, @RequestBody RuleManager.RuleSpec spec) {
        return ruleManager.update(name, spec);
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {
        ruleManager.delete(name);
        return Map.of("deleted", name);
    }

    /** Per-rule runtime status from the last evaluation pass. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        List<RuleStatus> statuses = alertEngine.ruleStatuses();
        return Map.of("count", statuses.size(), "rules", statuses);
    }
}
