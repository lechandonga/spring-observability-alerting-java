package com.github.lechandonga.springobservabilityalertingjava.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime rule management on top of {@link AlertEngine}.
 *
 * <p>All mutations are serialized on this monitor, so the rule-count limit is
 * a hard cap even under concurrent submissions, and every accepted change is
 * applied to the engine as one atomic, fully-validated set. Validation happens
 * before any mutation, hence a rejected change leaves the live rules and all
 * alert state untouched. Runtime changes are in-memory only: a restart falls
 * back to the rules loaded from configuration.
 */
public final class RuleManager {

    private static final Logger log = LoggerFactory.getLogger(RuleManager.class);

    private final AlertEngine engine;
    private final int maxRules;

    public RuleManager(AlertEngine engine, int maxRules) {
        this.engine = engine;
        this.maxRules = maxRules;
    }

    public List<AlertRule> list() {
        return engine.currentRules();
    }

    public int maxRules() {
        return maxRules;
    }

    public synchronized AlertRule add(RuleSpec spec) {
        AlertRule rule = validate(spec);
        List<AlertRule> current = engine.currentRules();
        if (current.stream().anyMatch(r -> r.name().equals(rule.name()))) {
            throw rejected(RuleManagementException.Code.DUPLICATE_RULE,
                    "add", rule.name(), "a rule named '" + rule.name() + "' already exists");
        }
        if (current.size() >= maxRules) {
            throw rejected(RuleManagementException.Code.RULE_LIMIT_EXCEEDED,
                    "add", rule.name(), "rule limit " + maxRules + " reached");
        }
        List<AlertRule> next = new ArrayList<>(current);
        next.add(rule);
        engine.updateRules(next);
        log.info("rule added: {}", rule);
        return rule;
    }

    /**
     * Replaces the rule named {@code name} with the given definition. The new
     * definition may use a different name (rename), provided it does not
     * collide with another existing rule.
     */
    public synchronized AlertRule update(String name, RuleSpec spec) {
        AlertRule rule = validate(spec);
        List<AlertRule> current = engine.currentRules();
        int index = indexOf(current, name);
        if (index < 0) {
            throw rejected(RuleManagementException.Code.RULE_NOT_FOUND,
                    "update", name, "no rule named '" + name + "'");
        }
        if (!rule.name().equals(name)
                && current.stream().anyMatch(r -> r.name().equals(rule.name()))) {
            throw rejected(RuleManagementException.Code.DUPLICATE_RULE,
                    "update", name, "a rule named '" + rule.name() + "' already exists");
        }
        List<AlertRule> next = new ArrayList<>(current);
        next.set(index, rule);
        engine.updateRules(next);
        log.info("rule updated: name={} new={}", name, rule);
        return rule;
    }

    public synchronized void delete(String name) {
        List<AlertRule> current = engine.currentRules();
        int index = indexOf(current, name);
        if (index < 0) {
            throw rejected(RuleManagementException.Code.RULE_NOT_FOUND,
                    "delete", name, "no rule named '" + name + "'");
        }
        List<AlertRule> next = new ArrayList<>(current);
        AlertRule removed = next.remove(index);
        engine.updateRules(next);
        log.info("rule deleted: {}", removed);
    }

    private static int indexOf(List<AlertRule> rules, String name) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static AlertRule validate(RuleSpec spec) {
        if (spec == null) {
            throw rejected(RuleManagementException.Code.VALIDATION_FAILED, "validate", null,
                    "request body is required");
        }
        requireText(spec.name(), "name");
        requireText(spec.endpoint(), "endpoint");
        requireText(spec.statusClass(), "statusClass");
        if (spec.threshold() == null || spec.threshold() < 1) {
            throw rejected(RuleManagementException.Code.VALIDATION_FAILED, "validate", spec.name(),
                    "threshold must be an integer >= 1");
        }
        if (spec.window() == null || spec.window().isZero() || spec.window().isNegative()) {
            throw rejected(RuleManagementException.Code.VALIDATION_FAILED, "validate", spec.name(),
                    "window must be a positive ISO-8601 duration (e.g. PT1M)");
        }
        if (spec.cooldown() == null || spec.cooldown().isNegative()) {
            throw rejected(RuleManagementException.Code.VALIDATION_FAILED, "validate", spec.name(),
                    "cooldown must be a non-negative ISO-8601 duration (e.g. PT2M)");
        }
        return new AlertRule(spec.name().trim(), spec.endpoint().trim(), spec.statusClass().trim(),
                spec.threshold(), spec.window(), spec.cooldown());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw rejected(RuleManagementException.Code.VALIDATION_FAILED, "validate", null,
                    "field '" + field + "' is required and must not be blank");
        }
    }

    private static RuleManagementException rejected(RuleManagementException.Code code,
            String op, String ruleName, String reason) {
        log.warn("rule change rejected: op={} rule={} code={} reason={}", op, ruleName, code, reason);
        return new RuleManagementException(code, reason);
    }

    /** Raw rule definition as accepted from the API; all fields may be null (validated later). */
    public record RuleSpec(String name, String endpoint, String statusClass,
            Integer threshold, Duration window, Duration cooldown) {
    }
}
