package com.github.lechandonga.springobservabilityalertingjava.alert;

/**
 * Rejected rule change. The {@link Reason} distinguishes why, so API clients
 * can tell a missing field from a duplicate name or an exceeded limit.
 * A rejected change never touches the live rule set or any alert state.
 */
public final class RuleValidationException extends RuntimeException {

    public enum Reason {
        /** A required field is null or blank. */
        MISSING_FIELD,
        /** A field value is out of range (threshold/window/cooldown). */
        INVALID_VALUE,
        /** A rule with the same name already exists. */
        DUPLICATE_NAME,
        /** The named rule does not exist (update/delete). */
        RULE_NOT_FOUND,
        /** The configured rule count limit would be exceeded. */
        LIMIT_EXCEEDED
    }

    private final Reason reason;

    public RuleValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
