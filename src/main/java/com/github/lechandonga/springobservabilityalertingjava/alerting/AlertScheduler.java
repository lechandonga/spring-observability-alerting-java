package com.github.lechandonga.springobservabilityalertingjava.alerting;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每秒驱动一次告警评估：先按当前整秒评估所有规则，再回收空闲序列。
 * tick 取 epochSecond，同一份输入在任何机器上重放结论都一致。
 */
@Component
public class AlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);

    private final AlertEngine engine;
    private final AlertProperties properties;

    public AlertScheduler(AlertEngine engine, AlertProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        if (engine != null) {
            log.info("alert scheduler active, idleGraceSeconds={}", properties.getIdleGraceSeconds());
        }
    }

    @Scheduled(fixedDelayString = "${observability.alert.tick-ms:1000}")
    public void tick() {
        if (engine == null || !properties.isEnabled()) {
            return;
        }
        long now = java.time.Instant.now().getEpochSecond();
        engine.evaluate(now);
        engine.evictIdle(now, properties.getIdleGraceSeconds());
    }

    @PreDestroy
    void stop() {
        log.info("alert scheduler stopped");
    }
}
