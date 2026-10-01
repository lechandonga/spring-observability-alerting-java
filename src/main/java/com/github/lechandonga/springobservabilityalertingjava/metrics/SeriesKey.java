package com.github.lechandonga.springobservabilityalertingjava.metrics;

/**
 * Identity of one metric series: endpoint + result status class (e.g. "2xx").
 */
public record SeriesKey(String endpoint, String statusClass) {

    public SeriesKey {
        endpoint = DimensionSanitizer.sanitize(endpoint);
        statusClass = DimensionSanitizer.sanitize(statusClass);
    }

    @Override
    public String toString() {
        return endpoint + "|" + statusClass;
    }
}
