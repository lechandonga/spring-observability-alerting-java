package com.github.lechandonga.springobservabilityalertingjava.metrics;

import java.util.regex.Pattern;

/**
 * Validates and normalizes metric dimension values.
 *
 * <p>Rules are deterministic so the same input always maps to the same outcome:
 * <ul>
 *   <li>null/blank or values not matching the allowed character set
 *       ({@code [a-zA-Z0-9_\-./:{} ]}, 1-128 chars) are
 *       merged into the distinguishable bucket {@value #INVALID_BUCKET};</li>
 *   <li>when the registry is full, unknown values are merged into
 *       {@value #OVERFLOW_BUCKET} by the registry (not silently dropped).</li>
 * </ul>
 */
public final class DimensionSanitizer {

    public static final String INVALID_BUCKET = "_invalid_";
    public static final String OVERFLOW_BUCKET = "_overflow_";

    private static final Pattern VALID = Pattern.compile("[a-zA-Z0-9_\\-./:{} ]{1,128}");

    private DimensionSanitizer() {
    }

    /** Returns the original value if legal, otherwise {@value #INVALID_BUCKET}. */
    public static String sanitize(String value) {
        if (value == null || !VALID.matcher(value).matches()) {
            return INVALID_BUCKET;
        }
        return value;
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }
}
