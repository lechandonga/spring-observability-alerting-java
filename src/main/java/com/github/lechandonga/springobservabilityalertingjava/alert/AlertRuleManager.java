package com.github.lechandonga.springobservabilityalertingjava.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runtime rule management on top of {@link AlertEngine}. All mutations are
 * serialized through one lock, so the configured rule limit is a hard cap
 * even under concurrent submissions: check-and-apply is one critical
 * section. Validation happens fully before anything is applied, hence a
 * rejected change leaves the live rule set and all alert state untouched.
 * Every accepted and rejected change is logged with its reason.
 */
public final class AlertRuleManager {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleManager.class);

    private final AlertEngine engine;
    private final int maxRules;
    private final ReentrantLock lock = new ReentrantLock();

    public AlertRuleManager(AlertEngine engine, int maxRules) {
        this.engine = engine;
        this.maxRules = maxRules;
    }

    public List<AlertRule> list() {
        return engine.currentRules();
    }

    public int maxRules() {
        return maxRules;
    }

    public AlertRule add(RuleDefinition def) {
        lock.lock();
        try {
            AlertRule rule = validate(def);
            List<AlertRule> current = engine.currentRules();
            if (contains(current, rule.name())) {
                throw rejected(RuleValidationException.Reason.DUPLICATE_NAME,
                        "rule '" + rule.name() + "' already exists");
            }
            if (current.size() >= maxRules) {
                throw rejected(RuleValidationException.Reason.LIMIT_EXCEEDED,
                        "rule limit " + maxRules + " reached");
            }
            List<AlertRule> next = new ArrayList<>(current);
            next.add(rule);
            engine.updateRules(next);
            log.info("rule added: {}", rule);
            return rule;
        } finally {
            lock.unlock();
        }
    }

    public AlertRule update(String name, RuleDefinition def) {
        lock.lock();
        try {
            AlertRule rule = validate(new RuleDefinition(name, def.endpoint(), def.statusClass(),
                    def.threshold(), def.window(), def.cooldown()));
            List<AlertRule> current = engine.currentRules();
            int index = indexOf(current, name);
            if (index < 0) {
                throw rejected(RuleValidationException.Reason.RULE_NOT_FOUND,
                        "rule '" + name + "' does not exist");
            }
            List<AlertRule> next = new ArrayList<>(current);
            next.set(index, rule);
            engine.updateRules(next);
            log.info("rule updated: name={} new={}", name, rule);
            return rule;
        } finally {
            lock.unlock();
        }
    }

    public void delete(String name) {
        lock.lock();
        try {
            List<AlertRule> current = engine.currentRules();
            int index = indexOf(current, name);
            if (index < 0) {
                throw rejected(RuleValidationException.Reason.RULE_NOT_FOUND,
                        "rule '" + name + "' does not exist");
            }
            List<AlertRule> next = new ArrayList<>(current);
            AlertRule removed = next.remove(index);
            engine.updateRules(next);
            log.info("rule deleted: {}", removed);
        } finally {
            lock.unlock();
        }
    }

    private RuleValidationException rejected(RuleValidationException.Reason reason, String message) {
        log.warn("rule change rejected: reason={} detail={}", reason, message);
        return new RuleValidationException(reason, message);
    }

    private static boolean contains(List<AlertRule> rules, String name) {
        return indexOf(rules, name) >= 0;
    }

    private static int indexOf(List<AlertRule> rules, String name) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private AlertRule validate(RuleDefinition def) {
        requireText(def.name(), "name");
        requireText(def.endpoint(), "endpoint");
        requireText(def.statusClass(), "statusClass");
        if (def.threshold() == null) {
            throw rejected(RuleValidationException.Reason.MISSING_FIELD, "threshold is required");
        }
        if (def.window() == null) {
            throw rejected(RuleValidationException.Reason.MISSING_FIELD, "window is required");
        }
        if (def.cooldown() == null) {
            throw rejected(RuleValidationException.Reason.MISSING_FIELD, "cooldown is required");
        }
        if (def.threshold() < 1) {
            throw rejected(RuleValidationException.Reason.INVALID_VALUE,
                    "threshold must be >= 1, got " + def.threshold());
        }
        if (def.window().isNegative() || def.window().isZero()) {
            throw rejected(RuleValidationException.Reason.INVALID_VALUE,
                    "window must be > 0, got " + def.window());
        }
        if (def.cooldown().isNegative()) {
            throw rejected(RuleValidationException.Reason.INVALID_VALUE,
                    "cooldown must be >= 0, got " + def.cooldown());
        }
        return new AlertRule(def.name(), def.endpoint(), def.statusClass(),
                def.threshold(), def.window(), def.cooldown());
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw rejected(RuleValidationException.Reason.MISSING_FIELD, field + " is required");
        }
    }
}
