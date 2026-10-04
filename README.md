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
- 变更与评估互斥线性化：规则快照替换与每规则运行状态（触发/冷却/恢复记忆）的翻篇、回收在同一临界区内完成；每轮评估也在同一把锁内读取快照并改写运行状态。因此即使评估正在执行，一次已提交成功的改动也严格线性化在该轮评估的"完全之前"或"完全之后"，不存在跨边界的半新半旧判定。
- 长期无事件的序列（超过 `observability.idle-series-ttl`）在评估时被回收。
- 每次评估追加 `EvaluationRecord`（输入计数、阈值、判定）到审计日志；相同事件流加相同规则重放，结论完全一致（由测试 `evaluationIsReproducible` 验证）。

## 运行时规则管理

服务运行期间可通过 REST 接口查看、新增、修改、删除规则，无需重启：

| 接口 | 说明 |
| --- | --- |
| `GET /observability/rules` | 当前规则全集 + `maxRules` 上限 |
| `POST /observability/rules` | 新增规则（201；body 为完整规则定义） |
| `PUT /observability/rules/{name}` | 按名替换规则定义（body 中 `name` 忽略，以路径为准） |
| `DELETE /observability/rules/{name}` | 删除规则 |
| `GET /observability/rules/status` | 每条规则的运行状态视图 |

规则 JSON：`{"name":"errors","endpoint":"GET /api/fail","statusClass":"5xx","threshold":3,"window":"PT1M","cooldown":"PT2M"}`（`window`/`cooldown` 为 ISO-8601 时长）。

语义保证：

- **原子生效**：提交成功后下一次评估即看到整套新规则，绝不会看到改到一半的集合；多人同时改、边改边评估均安全（变更在管理器内串行化，评估侧只读快照）。
- **改定义即翻篇（评估并发时同样成立）**：规则定义变化与旧触发/冷却/恢复记忆的清除在同一个临界区完成，正在跑的评估要么整轮用旧定义+旧状态、要么整轮用新定义+干净状态。提交成功后的第一次评估起，按新定义从干净状态判定；窗口内未过期的事件数据照旧计入新定义。从改动提交点起，判定序列与"该规则服务启动时就加载新定义"逐轮一致（窗口事件数已满足新阈值时必然触发，不会被改动前遗留的冷却/未恢复压住）。
- **删除即回收、不可复活（评估并发时同样成立）**：删除与运行时状态释放在同一个临界区完成；正在执行的评估不可能把已删规则的状态重新建出来，因此删除提交成功后该规则不再产生任何新结论、告警或状态视图条目。反复增删状态不增长；删后重建同名规则从干净状态开始。
- **状态视图一致性**：`GET /observability/rules/status` 与评估共用同一把锁取快照，读到的定义与冷却/恢复状态必然配对——不会出现"新定义配着旧冷却"或"已删规则残留在视图里"；视图内容与评估日志中该规则最后一条记录对得上。
- **失败零副作用**：任何被拒绝的改动不会影响现行规则与告警状态；成功与失败均留日志（失败带 `reason`）。

错误语义（响应体含 `reason` 字段，可区分原因）：

| `reason` | HTTP | 含义 |
| --- | --- | --- |
| `MISSING_FIELD` | 400 | 必填字段缺失或空白 |
| `INVALID_VALUE` | 400 | threshold < 1、window <= 0、cooldown < 0 |
| `DUPLICATE_NAME` | 409 | 新增重名规则 |
| `RULE_NOT_FOUND` | 404 | 修改/删除不存在的规则 |
| `LIMIT_EXCEEDED` | 409 | 达到 `observability.max-rules` 上限（并发提交同样是硬约束） |

### 运行状态视图

`GET /observability/rules/status` 每条规则返回：`windowCount`（最近评估的窗口计数）、`lastDecision`、`lastEvaluatedAt`、`inCooldown`/`cooldownRemaining`、`recovered`（是否已恢复可再次触发）、`lastFiredAt`。数据来自评估器自身状态，与评估日志逐条对应，可解释"为什么没响"；查询为只读快照，不干扰评估；规则被改/删后视图立即刷新或消失。

### 兼容范围

- 启动时照旧从 `observability.rules[n].*` 加载配置文件规则。
- 运行时改动**不做持久化**：重启后回到配置文件那一套。

## 配置项

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `observability.max-series` | 500 | 正常指标序列数上限 |
| `observability.idle-series-ttl` | PT30M | 告警序列空闲回收时间 |
| `observability.evaluation-interval` | PT10S | 告警评估周期 |
| `observability.max-rules` | 100 | 告警规则条数硬上限（配置 + 运行时新增合计） |
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

### 运行时改规则并观察评估行为变化

启动后把内置规则（`fail-endpoint-errors`，threshold=5）改严到 2：

    curl -X PUT localhost:8080/observability/rules/fail-endpoint-errors \
      -H 'Content-Type: application/json' \
      -d '{"endpoint":"GET /api/fail","statusClass":"5xx","threshold":2,"window":"PT1M","cooldown":"PT1M"}'

立即生效，无需重启。打 2 次失败请求，等下一个评估周期（默认 10s）：

    curl localhost:8080/api/fail; curl localhost:8080/api/fail
    sleep 11
    curl localhost:8080/observability/rules/status

可见 `lastDecision=FIRED`、`inCooldown=true`、`windowCount=2`；再查 `curl localhost:8080/observability/alerts` 的 `fired` 列表有对应告警。删除规则后（`curl -X DELETE localhost:8080/observability/rules/fail-endpoint-errors`）状态视图立即不再包含它；重启服务则恢复配置文件中的原规则。

### 并发交错（边评估边改/删规则）的语义与复现

保证（评估持续运行时也成立）：

- 改定义提交成功即"翻篇"：旧的触发/冷却/未恢复记忆与定义替换在同一临界区完成，之后的判定与从头加载新定义完全一致——窗口内事件数已达到新阈值时第一次评估必为 `FIRED`，不会被旧冷却压住。
- 删除提交成功即"回收"：已删规则不再产生任何 `EvaluationRecord`/告警/状态条目，正在跑的评估不会把它的运行状态重新建出来；重建同名规则从干净状态开始，反复增删计数不增长。
- 被拒绝的非法改动在并发压力下对现行规则、告警和运行状态零影响。

自动化复现（评估线程持续跑，同时并发改定义、删除、重建同名规则，重复 12 轮，并混入非法提交）：

    mvn test -Dtest=AlertEngineConcurrencyTest

该测试（`AlertEngineConcurrencyTest`）覆盖并断言：

- 旧定义（threshold=3）触发后处于冷却/未恢复时，在 4 个评估线程持续运行中改为 threshold=2，窗口内已有 5 个事件——新定义提交后的第一条评估记录必须是 `FIRED`（旧实现会稳定复现 `SUPPRESSED_BY_COOLDOWN`/`SUPPRESSED_NOT_RECOVERED`），且同一时刻只触发一次。
- 用另一个"启动时就直接加载新定义"的引擎重放同样的事件流，之后 16 个时间 tick（推进冷却、窗口排空恢复、再次触发）的每次 `decision`/`windowCount`/是否触发逐点一致。
- 删除提交后再评估一轮，删除规则不产生任何新记录和告警，`ruleRuntimeCount` 立即下降、状态视图立即消失；重建同名规则立即触发；25 次增删循环计数恒定不增长。
- 采样线程在整个并发窗口内持续校验运行状态与现行规则一一对应（无复活的孤立 runtime）、不会出现"新定义+旧冷却"，且全程并发提交缺失字段、threshold=0、改/删不存在规则等非法改动。

日志沿用现有风格，每轮打印 `round=n scenario=REDEFINE|DELETE input={...}` 与 `decision basis: ...`，可看出各场景的输入和判定依据。预期结果：测试通过；在修复前的实现上该测试在第 0 轮即失败（新定义首条结论为 `SUPPRESSED_BY_COOLDOWN` 而非 `FIRED`）。

手工观察并发行为：把评估周期调短后一边持续制造失败流量，一边循环改定义/删除/重建并轮询状态视图与告警列表——

    mvn spring-boot:run -Dspring-boot.run.arguments=--observability.evaluation-interval=PT1S
    # 终端 1：持续流量
    while true; do for i in $(seq 1 5); do curl -s localhost:8080/api/fail >/dev/null; done; sleep 0.2; done
    # 终端 2：反复改严/改宽定义并观察
    while true; do
      curl -s -X PUT localhost:8080/observability/rules/fail-endpoint-errors -H 'Content-Type: application/json' \
        -d '{"endpoint":"GET /api/fail","statusClass":"5xx","threshold":2,"window":"PT1M","cooldown":"PT1M"}' >/dev/null
      curl -s localhost:8080/observability/rules/status | grep -o '"lastDecision":"[A-Z_]*"'; sleep 1
      curl -s -X DELETE localhost:8080/observability/rules/fail-endpoint-errors >/dev/null
      curl -s localhost:8080/observability/rules/status; sleep 1
    done

预期：删除后状态视图立即不含该规则、无新告警出现；重建后按当时定义干净触发；`/observability/alerts` 中不存在删除提交之后该规则的新告警。
