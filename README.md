# Spring Observability & Alerting Bootstrap

请求级可观测与告警能力：按接口和结果状态累计调用量与时延分布、请求标识生成/透传/日志关联、基于滑动窗口的阈值告警与冷却。

## 指标模型

- 序列键：`endpoint|statusClass`，例如 `GET /api/hello/{name}|2xx`。
- `endpoint` 使用 Spring MVC 的路由模板（`bestMatchingPattern`），而非原始 URI，避免路径参数撑爆基数。
- `statusClass` 为 `1xx`~`5xx`。
- 每个序列包含：调用计数（`LongAdder`）+ 固定桶时延直方图（ms，桶界 5/10/25/50/100/250/500/1000/2500/5000/+Inf，`AtomicLongArray`）。
- 并发语义：所有写入为无锁原子累加；快照中计数与直方图均为单调值，并发读不会看到计数丢失或分布错位。

### 维度上限与归并规则（确定且可区分）

| 情况 | 结果 | 可观测性 |
| --- | --- | --- |
| 维度值非法（空、超长、含非法字符） | 归并到 `_invalid_` 序列 | `invalidDimensionObservations` 计数 |
| 序列数达到 `observability.max-series` 后出现新序列 | 归并到 `_overflow_` 序列（按状态类区分） | `overflowedObservations` 计数 |

超限或非法都不会静默丢弃，也不会无限增长：`_overflow_` 序列数量以状态类个数（不超过 5）为上界。

## 请求标识

`RequestIdFilter`（最高优先级）处理 `X-Request-Id`：

| 上游标识情况 | 处理 | `X-Request-Id-Source` |
| --- | --- | --- |
| 缺失或空白 | 生成 UUID | `generated` |
| 重复头（多个值） | 全部弃用，重新生成 | `regenerated-duplicate` |
| 非法（非 `[A-Za-z0-9-]` 且长度 8-64） | 重新生成 | `regenerated-invalid` |
| 合法 | 原样透传 | `propagated` |

- 生效标识始终写入响应头 `X-Request-Id`，并放入 MDC（`requestId`），控制台日志格式包含该标识。
- 异常路径：指标在 `finally` 中记录（异常也产生 5xx 观测），`GlobalExceptionHandler` 原样记录堆栈并回显原始错误类型与消息，不吞错、不改写。

## 告警规则语义

规则（`AlertRule`）：`name` + `endpoint` + `statusClass` + `threshold` + `window` + `cooldown`。

- 滑动窗口内事件数达到 `threshold`，且规则已恢复（armed）且不在冷却期，则触发一次（`FIRED`）。
- 冷却期内不重复触发（`SUPPRESSED_BY_COOLDOWN`）。
- 触发后必须观察到窗口计数回落到阈值以下（`OK`，重新武装），之后才能再次触发；否则 `SUPPRESSED_NOT_RECOVERED`。
- 规则更新原子生效：整套规则存于单个 `AtomicReference`，评估线程只会看到完整的新或旧集合，不会看到半更新状态。
- 长期无事件的序列（超过 `observability.idle-series-ttl`）在评估时被回收。
- 每次评估追加 `EvaluationRecord`（输入计数、阈值、判定）到审计日志；相同事件流加相同规则重放，结论完全一致（由测试 `evaluationIsReproducible` 验证）。

## 配置项

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `observability.max-series` | 500 | 正常指标序列数上限 |
| `observability.idle-series-ttl` | PT30M | 告警序列空闲回收时间 |
| `observability.evaluation-interval` | PT10S | 告警评估周期 |
| `observability.rules[n].*` | 无 | 告警规则：name、endpoint、status-class、threshold、window、cooldown |

## 本地验证

运行全部单测（并发写入、维度超限、标识异常、告警冷却）：

    mvn test

启动服务（端口 8080）并产生流量：

    mvn spring-boot:run
    curl -H 'X-Request-Id: demo-req-001' localhost:8080/api/hello/world
    for i in $(seq 1 6); do curl localhost:8080/api/fail; done

查看指标与告警：

    curl localhost:8080/observability/metrics
    curl localhost:8080/observability/alerts

日志每行都带请求标识，可用 `grep <requestId>` 还原单次请求链路。
