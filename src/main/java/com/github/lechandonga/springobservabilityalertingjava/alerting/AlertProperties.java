package com.github.lechandonga.springobservabilityalertingjava.alerting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 告警相关配置，前缀 {@code observability.alert}。
 */
@ConfigurationProperties(prefix = "observability.alert")
public class AlertProperties {

    /** 是否启用每秒一次的定时评估与空闲序列回收。 */
    private boolean enabled = true;
    /** 无数据序列在窗口结束后额外保留的秒数，超过即回收。 */
    private int idleGraceSeconds = 60;
    /** 启动时加载的静态规则（生产中也可通过 replaceRules 热更新）。 */
    private List<AlertRuleConfig> rules = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getIdleGraceSeconds() {
        return idleGraceSeconds;
    }

    public void setIdleGraceSeconds(int idleGraceSeconds) {
        this.idleGraceSeconds = idleGraceSeconds;
    }

    public List<AlertRuleConfig> getRules() {
        return rules;
    }

    public void setRules(List<AlertRuleConfig> rules) {
        this.rules = rules;
    }

    public static class AlertRuleConfig {
        private String id;
        private String endpoint;
        private String status = "*";
        private AlertMetric metric = AlertMetric.CALLS;
        private int windowSeconds = 60;
        private long threshold = 100;
        private int cooldownSeconds = 300;

        public AlertRule toRule() {
            return new AlertRule(id, endpoint, status, metric, windowSeconds, threshold, cooldownSeconds);
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public AlertMetric getMetric() {
            return metric;
        }

        public void setMetric(AlertMetric metric) {
            this.metric = metric;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public long getThreshold() {
            return threshold;
        }

        public void setThreshold(long threshold) {
            this.threshold = threshold;
        }

        public int getCooldownSeconds() {
            return cooldownSeconds;
        }

        public void setCooldownSeconds(int cooldownSeconds) {
            this.cooldownSeconds = cooldownSeconds;
        }
    }
}
