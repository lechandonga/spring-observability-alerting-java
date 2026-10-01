package com.github.lechandonga.springobservabilityalertingjava.alert;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Single-threaded driver for {@link AlertEngine#evaluate()}, keeping the
 * evaluation log totally ordered.
 */
@Component
public class AlertScheduler {

    private final AlertEngine engine;

    public AlertScheduler(AlertEngine engine) {
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${observability.evaluation-interval:PT10S}")
    public void tick() {
        engine.evaluate();
    }
}
