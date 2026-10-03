package com.github.lechandonga.springobservabilityalertingjava.alert;

/**
 * Rejected rule-management operation. The {@link Code} distinguishes the
 * failure reason so API clients can tell validation errors, name conflicts,
 * missing rules and limit violations apart. A rejected operation never
 * mutates the live rule set or any alert state.
 */
public final class RuleManagementException extends RuntimeException {

    public enum Code {
        /** Missing fields or unreasonable threshold/window/cooldown values. */
        VALIDATION_FAILED,
        /** A rule with the same name already exists (on create/rename). */
        DUPLICATE_RULE,
        /** The rule to update or delete does not exist. */
        RULE_NOT_FOUND,
        /** The configured rule-count limit would be exceeded. */
        RULE_LIMIT_EXCEEDED
    }

    private final Code code;

    public RuleManagementException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
