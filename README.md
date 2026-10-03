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

## 运行时规则管理

规则除了写配置，还可以在服务运行期间通过 REST 接口增删改，无需重启：

| 接口 | 说明 |
| --- | --- |
| `GET /observability/rules` | 列出当前规则（含 `maxRules` 与 `count`） |
| `POST /observability/rules` | 新增规则，成功返回 201 |
| `PUT /observability/rules/{name}` | 替换指定规则的定义（body 里的 name 可不同，即改名） |
| `DELETE /observability/rules/{name}` | 删除规则 |
| `GET /observability/rules/status` | 每条规则最近一次评估的运行状态 |

规则体示例（`window`/`cooldown` 为 ISO-8601 时长）：

```json
{"name":"fail-endpoint-errors","endpoint":"GET /api/fail","statusClass":"5xx",
 "threshold":5,"window":"PT1M","cooldown":"PT2M"}
```

生效与一致性语义：

- 改动提交后对下一次评估立即整套生效：评估只会看到完整的新或旧规则集合，不会看到改到一半的状态（评估与规则变更共用同一把内部锁）。
- 规则定义被修改后"翻篇"：该规则的触发/冷却/恢复记忆全部清零，按新定义从干净状态重新开始；窗口里尚未过期的事件照常参与新定义的统计。从改动那一刻起，同样的流量和时间推进下，结论与"这条规则从头就是新定义"完全一致。
- 规则删除后立即退出评估，其运行时状态与状态视图条目一并释放，反复增删不会累积。
- 运行时改动只在内存中生效，不做持久化：重启后回到配置文件加载的那套规则。

校验与错误语义（被拒绝的改动对现行规则和告警状态零影响，成功与失败均留日志）：

| 情况 | HTTP | 响应 `error` |
| --- | --- | --- |
| 字段缺失/空白、threshold<1、window<=0、cooldown<0 | 400 | `VALIDATION_FAILED` |
| 新增或改名为已存在的规则名 | 409 | `DUPLICATE_RULE` |
| 修改或删除不存在的规则 | 404 | `RULE_NOT_FOUND` |
| 规则数达到 `observability.max-rules` 上限 | 409 | `RULE_LIMIT_EXCEEDED` |

上限是硬约束：所有变更在 `RuleManager` 上串行化，并发提交也无法冲破；校验全部通过后才原子替换规则集。

### 运行状态视图

`GET /observability/rules/status` 返回每条规则最近一次评估时的状态，与评估审计日志（`GET /observability/alerts` 的 `evaluations`）一一对应，可用来解释"为什么这条没响"：

- `windowCount` / `threshold`：评估时窗口内计数与阈值；
- `inCooldown` / `cooldownRemaining`：是否仍在冷却期及剩余时长；
- `recovered`：是否已回落到阈值以下、重新武装可再次触发；
- `lastFiredAt`：上次触发时间（未触发过为 null）；
- `lastDecision`：最近一次判定（`OK` / `FIRED` / `SUPPRESSED_BY_COOLDOWN` / `SUPPRESSED_NOT_RECOVERED`）。

规则被改或被删后，其状态条目立即重置或消失；查询为只读快照，不干扰评估。

## 配置项

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `observability.max-series` | 500 | 正常指标序列数上限 |
| `observability.idle-series-ttl` | PT30M | 告警序列空闲回收时间 |
| `observability.evaluation-interval` | PT10S | 告警评估周期 |
| `observability.max-rules` | 100 | 告警规则条数硬上限（配置 + 运行时） |
| `observability.rules[n].*` | 无 | 告警规则：name、endpoint、status-class、threshold、window、cooldown |

## 本地验证

运行全部单测（并发写入、维度超限、标识异常、告警冷却、运行时规则管理）：

    mvn test

启动服务（端口 8080）并产生流量：

    mvn spring-boot:run
    curl -H 'X-Request-Id: demo-req-001' localhost:8080/api/hello/world
    for i in $(seq 1 6); do curl localhost:8080/api/fail; done

查看指标与告警：

    curl localhost:8080/observability/metrics
    curl localhost:8080/observability/alerts

运行时改一条规则并观察评估行为跟着变：

    # 把内置规则阈值从 5 降到 2，无需重启
    curl -X PUT localhost:8080/observability/rules/fail-endpoint-errors \
      -H 'Content-Type: application/json' \
      -d '{"name":"fail-endpoint-errors","endpoint":"GET /api/fail","statusClass":"5xx","threshold":2,"window":"PT1M","cooldown":"PT2M"}'

    # 再打 2 次失败请求，下一个评估周期（10s 内）就会触发告警
    curl localhost:8080/api/fail; curl localhost:8080/api/fail
    sleep 10
    curl localhost:8080/observability/rules/status   # 看 lastDecision=FIRED、lastFiredAt
    curl localhost:8080/observability/alerts          # fired 列表里出现该告警

    # 非法改动会被拒绝且现状不变
    curl -X POST localhost:8080/observability/rules -H 'Content-Type: application/json' \
      -d '{"name":"bad","threshold":0}'                # 400 VALIDATION_FAILED

日志每行都带请求标识，可用 `grep <requestId>` 还原单次请求链路。
